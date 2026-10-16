package com.moozaq.spike;
import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.*;
import java.util.List;
public class CastOptionsProvider implements OptionsProvider {
    public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(true).setStopReceiverApplicationWhenEndingSession(false).build();
    }
    public List<SessionProvider> getAdditionalSessionProviders(Context context) { return null; }
}
