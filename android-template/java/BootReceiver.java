package __PKG__;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    SharedPreferences p = c.getSharedPreferences("alarms", 0);
    for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
      try {
        org.json.JSONObject o = new org.json.JSONObject((String) e.getValue());
        long ts = o.getLong("ts");
        if (ts > System.currentTimeMillis()) MainActivity.setAlarm(c, e.getKey(), ts, o.getString("t"), o.getString("m"), false);
      } catch (Exception x) { }
    }
  }
}
