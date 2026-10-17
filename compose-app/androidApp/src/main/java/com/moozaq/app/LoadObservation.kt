package com.moozaq.app

/** Keep a brief receiver failure even when its next status has no media session. */
internal class LoadObservation(private val content:String) {
    private var observedLoading=false
    var failure:String?=null
        private set
    fun observe(contentId:String?,error:Boolean) {
        if(contentId==content) observedLoading=true
        if(error && observedLoading && (contentId==null || contentId==content)) {
            failure="The speaker rejected this audio stream. No automatic retry was started."
        }
    }
    fun requireNotFailed() {failure?.let {throw IllegalStateException(it)}}
}
