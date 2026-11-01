package com.theerthkr.essentialmoments.ml

/**
 * Thrown when inference is requested before the relevant model file has been
 * downloaded and verified through the model setup flow.
 */
class ModelNotReadyException(val role: com.theerthkr.essentialmoments.ml.model.ModelRole) :
    IllegalStateException(
        "${role.displayName} model is not downloaded yet. Open Essential Moments and complete model setup."
    )
