package com.dmsouzamenezes.actions.runtime

import android.content.Context
import com.dmsouzamenezes.actions.runtime.litert.LiteRtFunctionGemmaIntentModel

/** Owns both the runtime and the native LiteRT-LM model lifecycle. */
class AndroidFunctionRuntimeSession internal constructor(
    val runtime: AndroidFunctionRuntime,
    private val model: LiteRtFunctionGemmaIntentModel,
) : AutoCloseable {
    override fun close() = model.close()
}

object FunctionGemmaRuntimeFactory {
    fun create(
        context: Context,
        modelPath: String,
        toolRegistry: ToolRegistry = RuntimeToolCatalog.createDefault(),
        policyEngine: PolicyEngine = DefaultPolicyEngine(),
    ): AndroidFunctionRuntimeSession {
        val appContext = context.applicationContext
        val model = LiteRtFunctionGemmaIntentModel(
            context = appContext,
            modelPath = modelPath,
        )
        val dispatcher = ActionDispatcher(
            actionContext = ActionContext(appContext),
            policyEngine = policyEngine,
        )
        return AndroidFunctionRuntimeSession(
            runtime = AndroidFunctionRuntime(
                intentModel = model,
                toolRegistry = toolRegistry,
                dispatcher = dispatcher,
            ),
            model = model,
        )
    }
}
