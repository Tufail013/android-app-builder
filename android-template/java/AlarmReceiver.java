package __PKG__;

import android.content.*;

public class AlarmReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    MainActivity.show(c, i.getStringExtra("t"), i.getStringExtra("m"));
    c.getSharedPreferences("alarms", 0).edit().remove(i.getStringExtra("id")).apply();
  }
}
