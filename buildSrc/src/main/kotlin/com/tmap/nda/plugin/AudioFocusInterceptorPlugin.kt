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
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return AudioFocusMethodVisitor(mv)
    }
}

class AudioFocusMethodVisitor(nextVisitor: MethodVisitor) : MethodVisitor(Opcodes.ASM9, nextVisitor) {
    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
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
