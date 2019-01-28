package de.plinzen.android.rttmanager.ranging;

import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingResult;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.plinzen.android.rttmanager.R;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

public class LaterationActivity  extends AppCompatActivity {
    private static final String EXTRA_WIFI_NETWORK = "WIFI_NETWORK";

    public static Intent builtIntent(final ArrayList<ScanResult> wifiNetworks, Context context) {
        Intent intent = new Intent(context, LaterationActivity.class);
        intent.putParcelableArrayListExtra(EXTRA_WIFI_NETWORK, wifiNetworks);
        return intent;
    }

    @BindView(R.id.logView)
    TextView logView;
    @BindView(R.id.startButton)
    Button startButton;
    @BindView(R.id.stopButton)
    Button stopButton;
    private List<Disposable> rangingDisposableList = new ArrayList<>();
    private RttRangingManager rangingManager;
    private List<ScanResult> wifiNetworks;
    Map<MacAddress, List<Integer>> movingAverage = new HashMap<>();
    Map<MacAddress, List<Integer>> average = new HashMap<>();
    Map<MacAddress, Integer> latestMeasurement = new HashMap<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lateration);
        ButterKnife.bind(this);
        rangingManager = new RttRangingManager(getApplicationContext());
        readIntentExtras();
        initUI();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopRanging();
    }

    private String buildLogString(final RangingResult result) {
        Long dateTime = new Date().getTime();
        String timestamp = dateTime.toString();
        MacAddress macAddress = result.getMacAddress();
        String mac = macAddress.toString();
        Long rangingTimestampMillis = result.getRangingTimestampMillis();
        String rangingTimeMillis = rangingTimestampMillis.toString();
        int distanceMm = result.getDistanceMm();

        String resultString = getString(R.string.log2, mac, (distanceMm/10)+500, logView.getText().toString());
        String logFileString = getString(R.string.log3, timestamp, mac, rangingTimeMillis, distanceMm, result.getDistanceStdDevMm(), result.getRssi());
        appendLog(logFileString);

        if (resultString.length() > 5000) {
            return resultString.substring(0, 5000);
        }
        return resultString;
    }


    private void initStartButtonListener() {
        startButton.setOnClickListener(view -> onStartButtonClicked());

    }

    private void initStopButtonListener() {
        stopButton.setOnClickListener(view -> stopRanging());

    }

    private void initUI() {
        setTitle(getString(R.string.lateration_activity_title));
        initStartButtonListener();
        initStopButtonListener();
        StringBuilder logText = new StringBuilder();
        logView.setText("");
        logText.append("AP found: \n");
        for (ScanResult result : wifiNetworks) {
            logText.append(result.BSSID);
            logText.append("\n");
        }
        logView.setText(logText.toString());
    }

    private void onStartButtonClicked() {
        logView.setText("");

        for (ScanResult result : wifiNetworks) {
            Disposable rangingDisposable = rangingManager.startRanging(result)
                    .repeat()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::writeOutput,
                            throwable -> {
                                Timber.e(throwable, "An unexpected error occurred while start ranging.");
                                Snackbar.make(logView, throwable.getMessage(), Snackbar.LENGTH_LONG).show();
                            });

            rangingDisposableList.add(rangingDisposable);
        }
    }

    private void readIntentExtras() {
        Bundle extras = getIntent().getExtras();
        wifiNetworks = (ArrayList<ScanResult>) extras.get(EXTRA_WIFI_NETWORK);
    }

    private void stopRanging() {
        for ( Disposable rangingDisposable : rangingDisposableList) {
            rangingDisposable.dispose();
        }
    }

    private void writeOutput(@NonNull final List<RangingResult> result) {
        if (result.isEmpty()) {
            Timber.d("EMPTY ranging result received.");
            return;
        }

        String text = "";
        for (RangingResult res : result) {
            if (res.getStatus() == RangingResult.STATUS_FAIL) return;
            buildLogString(res);

            //logView.setText(buildLogString(res));

            if (!movingAverage.containsKey(res.getMacAddress())) {
                average.put(res.getMacAddress(), new ArrayList<>());
                movingAverage.put(res.getMacAddress(), new ArrayList<>());
                latestMeasurement.put(res.getMacAddress(), 0);
            }
            average.get(res.getMacAddress()).add(res.getDistanceMm());
            movingAverage.get(res.getMacAddress()).add(res.getDistanceMm());
            latestMeasurement.put(res.getMacAddress(), res.getDistanceMm());

            if (movingAverage.get(res.getMacAddress()).size() > 50) {
                movingAverage.get(res.getMacAddress()).remove(0);
            }

            Long timestamp = new Date().getTime();
            Timber.d("%d,%s,%d,%d,%d", timestamp, res.getMacAddress(), res.getStatus(), res.getRangingTimestampMillis(), res.getDistanceMm());
        }

        for (MacAddress mac : movingAverage.keySet()) {
            Integer sumMovAvg = 0;
            Integer sumAvg = 0;
            for (Integer val : average.get(mac)) {
                sumAvg = sumAvg + val;
            }
            for (Integer val : movingAverage.get(mac)) {
                sumMovAvg = sumMovAvg + val;
            }

            Integer movavg = sumMovAvg / movingAverage.get(mac).size();
            Integer avg = sumAvg / average.get(mac).size();
            text += "Mac: " + mac.toString() + " - Average Distance: " + avg.toString() + "mm \n";
            text += "Mac: " + mac.toString() + " - Moving Average Distance: " + movavg.toString() + "mm \n";
            text += "Mac: " + mac.toString() + " - Current Distance: " + latestMeasurement.get(mac) + "mm \n";
        }
        logView.setText(text);
    }

    public void appendLog(String text)
    {
        Calendar cal = Calendar. getInstance();
        Date date=cal. getTime();
        DateFormat dateFormat = new SimpleDateFormat("dd-MM-HH-mm");
        String fileName = dateFormat.format(date) + "-Pixel2-MultiRanging.csv";
        File logFile = new File(this.getExternalFilesDir(null) + "/" + fileName);
        if (!logFile.exists())
        {
            try
            {
                logFile.createNewFile();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        try
        {
            //BufferedWriter for performance, true to set append to file flag
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(text);
            buf.newLine();
            buf.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

}
