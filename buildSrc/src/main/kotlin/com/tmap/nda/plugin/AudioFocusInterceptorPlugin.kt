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
        // Tmap SDK 내부 클래스들만 타겟팅 (필요시 전체로 확장 가능)
        val name = classData.className
        return name.startsWith("com.skt.tmap") || name.startsWith("com.tmapmobility")
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
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
