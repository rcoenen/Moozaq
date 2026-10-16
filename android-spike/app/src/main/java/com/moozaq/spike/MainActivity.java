package com.moozaq.spike;

import android.os.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.*;
import com.google.android.gms.cast.framework.*;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private CastContext cast;
    private RemoteMediaClient observed;
    private LinearLayout body;
    private TextView state, events;
    private EditText url, mime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> controls = new ArrayList<>();
    private final RemoteMediaClient.Callback mediaCallback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() { log("Receiver status updated"); refresh(); }
        @Override public void onMetadataUpdated() { refresh(); }
    };
    private final Runnable ticker = new Runnable() {
        public void run() { refresh(); handler.postDelayed(this, 1000); }
    };
    private final SessionManagerListener<CastSession> listener = new SessionManagerListener<>() {
        public void onSessionStarting(CastSession s) { event("Connecting"); }
        public void onSessionStarted(CastSession s, String id) { event("Connected"); }
        public void onSessionStartFailed(CastSession s, int e) { event("Connect failed: " + e); }
        public void onSessionEnding(CastSession s) { event("Disconnecting"); }
        public void onSessionEnded(CastSession s, int e) { event("Disconnected: " + e); }
        public void onSessionResuming(CastSession s, String id) { event("Reconnecting"); }
        public void onSessionResumed(CastSession s, boolean suspended) { event("Reconnected; reading receiver state"); }
        public void onSessionResumeFailed(CastSession s, int e) { event("Reconnect failed: " + e); }
        public void onSessionSuspended(CastSession s, int e) { event("Connection suspended: " + e); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int p = (int)(20 * getResources().getDisplayMetrics().density);
        body.setPadding(p,p,p,p); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        heading("Moozaq / playback lab");
        text("Two separate experiments. Nothing plays automatically when you reconnect.");
        heading("1 · Your YouTube mixes");
        text("Open a mix in YouTube Music and use its Cast button. Record whether it is available, keeps playing with the phone off, and reconnects. Moozaq does not control that app’s session.");
        String[] ids = {"7vy0H3ERRiA","MpoIuYCfEvQ","yYkRaDUMavk","zdIaNCDA4ZY","jsLX0Kp1Zpg","eJA9E7RXf5U"};
        for (int i=0;i<ids.length;i++) {
            String id=ids[i];
            text((i+1) + " · " + (i==0 ? "No Rush. Just Coffee." : id));
            button("Try in YouTube Music", () -> open("https://music.youtube.com/watch?v="+id, "com.google.android.apps.youtube.music"));
            button("Open original on YouTube", () -> open("https://www.youtube.com/watch?v="+id, "com.google.android.youtube"));
        }
        heading("2 · Native Cast control experiment");
        text("This tests a direct audio stream, not YouTube playback. Selecting a receiver can replace another app’s active session. Finish the YouTube Music test first.");
        state = text("Initializing Cast…");
        try {
            cast = CastContext.getSharedInstance(this);
            MediaRouteButton route = new MediaRouteButton(this);
            route.setContentDescription("Choose Cast device"); body.addView(route);
            CastButtonFactory.setUpMediaRouteButton(this, route);
        } catch (RuntimeException ex) { text("Cast unavailable: " + ex.getMessage()); }
        url = new EditText(this); url.setHint("Direct audio URL (HTTPS or local HTTP)"); url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getPreferences(0).getString("audioUrl", "")); body.addView(url);
        mime = new EditText(this); mime.setHint("MIME type"); mime.setSingleLine(true); mime.setText("audio/mpeg"); body.addView(mime);
        control("Load audio and play", this::load);
        control("Read current receiver state", () -> { if(client()!=null) result("Refresh", client().requestStatus()); });
        control("Play / resume", () -> { if(client()!=null) result("Play", client().play()); });
        control("Pause", () -> { if(client()!=null) result("Pause", client().pause()); });
        control("Stop", () -> { if(client()!=null) result("Stop", client().stop()); });
        control("Seek back 30 seconds", () -> seek(-30000));
        control("Seek forward 30 seconds", () -> seek(30000));
        control("Volume −", () -> volume(-0.05)); control("Volume +", () -> volume(0.05));
        control("Disconnect remote; leave receiver running", () -> cast.getSessionManager().endCurrentSession(false));
        heading("3 · Observations");
        text("Select a result after each hardware test. Results and event logs persist on this phone. Export through Android’s share sheet when ready.");
        String[] checks={"YouTube Music: exact mix available", "YouTube Music: Nest playback", "YouTube Music: phone off, audio continues", "YouTube Music: reconnect and control", "Direct audio: Nest playback", "Direct audio: phone off, audio continues", "Direct audio: cold-start reconnect without restart", "Direct audio: current position and volume sync", "Direct audio: play / pause / stop / seek / volume", "Speaker group", "Three-hour playback", "Network interruption recovery"};
        for (String check:checks) {
            text(check);
            Spinner status=new Spinner(this);
            String[] options={"Not tested", "Pass", "Fail", "Unavailable"};
            status.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,options));
            status.setSelection(getPreferences(0).getInt(check,0));
            status.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
                public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,int pos,long id){getPreferences(0).edit().putInt(check,pos).apply();}
            }); body.addView(status);
        }
        EditText notes = new EditText(this); notes.setHint("Test notes: video ID, Nest model/group, phone, account tier, interruptions…");
        notes.setMinLines(3); notes.setText(getPreferences(0).getString("notes","")); body.addView(notes);
        notes.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int c){getPreferences(0).edit().putString("notes",s.toString()).apply();}
            public void afterTextChanged(android.text.Editable e){}
        });
        button("Export test report", () -> {
            StringBuilder report=new StringBuilder("Moozaq spike report\nPhone: "+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n");
            String[] labels={"Not tested","Pass","Fail","Unavailable"};
            for(String check:checks) report.append(check).append(": ").append(labels[getPreferences(0).getInt(check,0)]).append("\n");
            report.append("\nNotes: ").append(notes.getText()).append("\n\nEvents:\n").append(getPreferences(0).getString("log",""));
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report.toString()),"Export test report"));
        });
        events=text(getPreferences(0).getString("log",""));
        refresh();
    }
    private void open(String address,String pkg) {
        log("Open " + address);
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(address)).setPackage(pkg)); }
        catch(ActivityNotFoundException e) {
            try {startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(address)));}
            catch(ActivityNotFoundException missing){log("No app or browser available");}
        }
    }
    private void event(String message) { log(message); bind(); refresh(); }
    private CastSession session() { return cast==null?null:cast.getSessionManager().getCurrentCastSession(); }
    private RemoteMediaClient client() { CastSession s=session(); return s!=null&&s.isConnected()?s.getRemoteMediaClient():null; }
    private void bind() {
        RemoteMediaClient next=client(); if(next==observed)return;
        if(observed!=null)observed.unregisterCallback(mediaCallback);
        observed=next;
        if(observed!=null) { observed.registerCallback(mediaCallback); observed.requestStatus(); }
    }
    private void refresh() {
        if(state==null)return;
        CastSession s=session(); boolean connected=s!=null&&s.isConnected();
        for(Button b:controls)b.setEnabled(connected);
        if(!connected){state.setText("Not connected · use the Cast button to connect or rejoin. No media will be loaded automatically.");return;}
        RemoteMediaClient c=client(); MediaInfo info=c==null?null:c.getMediaInfo();
        String title=info!=null&&info.getMetadata()!=null?info.getMetadata().getString(MediaMetadata.KEY_TITLE):"No media metadata";
        String playback="Unknown";
        if(c!=null) switch(c.getPlayerState()) {case MediaStatus.PLAYER_STATE_PLAYING:playback="Playing";break;case MediaStatus.PLAYER_STATE_PAUSED:playback="Paused";break;case MediaStatus.PLAYER_STATE_BUFFERING:playback="Buffering";break;case MediaStatus.PLAYER_STATE_IDLE:playback="Idle / stopped";break;}
        state.setText((s.getCastDevice()==null?"Receiver":s.getCastDevice().getFriendlyName())+"\n"+title+"\n"+playback+" · "+(c==null?0:c.getApproximateStreamPosition()/1000)+"s / "+(info==null?0:info.getStreamDuration()/1000)+"s\nVolume "+Math.round(s.getVolume()*100)+"%");
    }
    private void load() {
        String address=url.getText().toString().trim(); Uri parsed=Uri.parse(address);
        String host=parsed.getHost();
        if((!"https".equals(parsed.getScheme())&&!"http".equals(parsed.getScheme()))||host==null||host.equals("youtu.be")||host.endsWith("youtube.com")) {log("Enter a direct HTTP(S) audio URL, not a YouTube page");return;}
        if(client()==null)return;
        getPreferences(0).edit().putString("audioUrl",address).apply();
        MediaMetadata metadata=new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK); metadata.putString(MediaMetadata.KEY_TITLE,"Moozaq direct-audio test");
        MediaInfo info=new MediaInfo.Builder(address).setContentType(mime.getText().toString().trim()).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).build();
        result("Load",client().load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()));
    }
    private void seek(long delta) {
        RemoteMediaClient c=client(); if(c==null||c.getMediaStatus()==null)return;
        if(!c.getMediaStatus().isMediaCommandSupported(MediaStatus.COMMAND_SEEK)){log("Receiver does not support seeking this media");return;}
        long duration=c.getStreamDuration(); long position=Math.max(0,c.getApproximateStreamPosition()+delta);
        if(duration>0)position=Math.min(position,duration);
        result("Seek",c.seek(new MediaSeekOptions.Builder().setPosition(position).build()));
    }
    private void volume(double delta) {
        CastSession s=session(); if(s==null||!s.isConnected())return;
        try{s.setVolume(Math.max(0,Math.min(1,s.getVolume()+delta)));log("Volume change requested");}catch(Exception e){log("Volume failed: "+e.getMessage());}
    }
    private void result(String operation, com.google.android.gms.common.api.PendingResult<RemoteMediaClient.MediaChannelResult> pending) {
        pending.setResultCallback(r -> {log(operation+": "+r.getStatus().getStatusCode()+" "+(r.getStatus().isSuccess()?"accepted":"failed"));refresh();});
    }
    private void log(String message) {
        String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date())+" · "+message+"\n";
        String history=getPreferences(0).getString("log","")+line;
        if(history.length()>40000)history=history.substring(history.length()-40000);
        getPreferences(0).edit().putString("log",history).apply(); if(events!=null)events.setText(history);
    }
    private TextView text(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(16);t.setPadding(0,12,0,12);body.addView(t);return t;}
    private void heading(String value){TextView t=text(value);t.setTextSize(23);t.setTypeface(null,android.graphics.Typeface.BOLD);}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v -> action.run());body.addView(b);return b;}
    private void control(String label,Runnable action){controls.add(button(label,action));}
    @Override protected void onResume(){super.onResume();if(cast!=null){cast.getSessionManager().addSessionManagerListener(listener,CastSession.class);bind();}handler.post(ticker);}
    @Override protected void onPause(){handler.removeCallbacks(ticker);if(cast!=null)cast.getSessionManager().removeSessionManagerListener(listener,CastSession.class);if(observed!=null){observed.unregisterCallback(mediaCallback);observed=null;}super.onPause();}
}
