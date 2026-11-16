package com.tmap.nda.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class AudioFocusInterceptorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
            androidComponents.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    AudioFocusInterceptorFactory::class.java,
                    InstrumentationScope.ALL
                ) {}
            }
        }
    }
}

abstract class AudioFocusInterceptorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return AudioFocusClassVisitor(nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        // Tmap SDK 내부 클래스들 + 카카오내비 SDK 내부 클래스들 타겟팅
        // v: 재억 제보(2026-09-09) - playSoundEffect(int) "띵띵" 소리가 실제로는
        // KakaoNaviActivity가 화면 위에 떠 있을 때 발생함이 로그로 확인됨(직전에 카카오
        // 음소거하면 소리가 사라진다는 실사용 확인도 일치). 원래 티맵 패키지만 대상이라
        // 카카오 SDK 내부 호출은 못 잡고 있었음 - com.kakaomobility 추가. #문제시 원복
        val name = classData.className
        return name.startsWith("com.skt.tmap") ||
            name.startsWith("com.tmapmobility") ||
            name.startsWith("com.kakaomobility")
    }
}

class AudioFocusClassVisitor(nextVisitor: ClassVisitor) : ClassVisitor(Opcodes.ASM9, nextVisitor) {
    private var kakaoHttp = false
    private var tmapVoice = false

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        // 2026-11-12 티맵 UI SDK의 안내음 합성 클래스(알림음 번호 걸러내기용). #문제시 원복
        tmapVoice = name == "com/tmapmobility/tmap/tmapsdk/ui/audio/e"
        // 카카오 SDK의 통신 클라이언트를 만드는 클래스(com.kakaomobility.knsdk.o.*)만 대상
        kakaoHttp = name.startsWith("com/kakaomobility/knsdk/o/")
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (tmapVoice && name == "a" && descriptor == "(Ljava/util/List;Lcom/tmapmobility/tmap/tmapsdk/ui/view/x;)V") {
            return TmapAlarmGateVisitor(AudioFocusMethodVisitor(mv, kakaoHttp))
        }
        return AudioFocusMethodVisitor(mv, kakaoHttp)
    }
}

class AudioFocusMethodVisitor(nextVisitor: MethodVisitor, private val kakaoHttp: Boolean = false) : MethodVisitor(Opcodes.ASM9, nextVisitor) {
    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        // 카카오 SDK 통신 클라이언트가 만들어지기 직전에 우리 중계 장치를 끼워 넣는다(KakaoHttpReplay.hook)
        if (kakaoHttp && opcode == Opcodes.INVOKEVIRTUAL && owner == "okhttp3/OkHttpClient\$Builder" && name == "build") {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/tmap/nda/KakaoHttpReplay",
                "hook",
                "(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient\$Builder;",
                false
            )
        }
        if (opcode == Opcodes.INVOKEVIRTUAL && owner == "android/media/AudioManager") {
            if (name == "requestAudioFocus" || name == "abandonAudioFocus" || name == "abandonAudioFocusRequest") {
                // 원본 descriptor: (Landroid/media/AudioManager$OnAudioFocusChangeListener;II)I
                // 변경 descriptor: (Landroid/media/AudioManager;Landroid/media/AudioManager$OnAudioFocusChangeListener;II)I
                val newDescriptor = descriptor.replace("(", "(Landroid/media/AudioManager;")
                
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/tmap/nda/AudioFocusHacker",
                    name,
                    newDescriptor,
                    false
                )
                return
            }
        }

        // v: 재억 요청(2026-09-09) - playSoundEffect(int)는 오디오 포커스 요청 과정을
        // 거치지 않고 바로 소리를 내는 별도 경로라서, 위 AudioFocusHacker 차단을 그대로
        // 뚫고 "띵띵띵띵" 소리가 났음(실제 발생 지점은 카카오내비 화면이 떠 있는 동안 -
        // 위 isInstrumentable()에 com.kakaomobility 추가함). 오디오 포커스 요청과 무관하게
        // 이 호출 자체를 가로채서 무시하도록 별도 규칙 추가. owner는 굳이 특정하지 않고
        // 시그니처만 매칭 - 어차피 isInstrumentable()에서 대상 SDK 클래스만 스캔하니 안전함.
        // #문제시 원복
        if (opcode == Opcodes.INVOKEVIRTUAL && name == "playSoundEffect" && descriptor == "(I)V") {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/tmap/nda/AudioFocusHacker",
                "playSoundEffect",
                "(Ljava/lang/Object;I)V",
                false
            )
            return
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

/**
 * audio.e.a(List, 콜백) 맨 앞에 "list = TmapAlarmFilter.filter(list); if (list === DROP) return;"를 끼워 넣는다.
 * (콜백의 실패 처리는 아무 동작도 안 해서, 재생을 건너뛰어도 안내 대기열이 멈추지 않음 - SDK 코드로 확인) #문제시 원복
 */
class TmapAlarmGateVisitor(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {
    override fun visitCode() {
        super.visitCode()
        super.visitVarInsn(Opcodes.ALOAD, 1)
        super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/tmap/nda/TmapAlarmFilter", "filter", "(Ljava/util/List;)Ljava/util/List;", false)
        super.visitVarInsn(Opcodes.ASTORE, 1)
        super.visitVarInsn(Opcodes.ALOAD, 1)
        super.visitFieldInsn(Opcodes.GETSTATIC, "com/tmap/nda/TmapAlarmFilter", "DROP", "Ljava/util/List;")
        val go = org.objectweb.asm.Label()
        super.visitJumpInsn(Opcodes.IF_ACMPNE, go)
        super.visitInsn(Opcodes.RETURN)
        super.visitLabel(go)
    }
}
