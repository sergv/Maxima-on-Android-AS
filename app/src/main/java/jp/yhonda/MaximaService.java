package jp.yhonda;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import static android.app.Notification.PRIORITY_LOW;

public class MaximaService extends Service {

    private static final int ONGOING_NOTIFICATION_ID = 1;


    private static final String APP_DATA_DIR = "/data/data/jp.yhonda";

    private static final String QEPCAD_DIR   = APP_DATA_DIR + "/files/additions/qepcad";

    private static final String APP_DATA_TMP_DIR = APP_DATA_DIR + "/files/tmp";
    public  static final String GNUPLOT_OUT      = APP_DATA_TMP_DIR + "/maxout.svg";
    private static final String QUEPCAD_INPUT    = APP_DATA_TMP_DIR + "/qepcad_input.txt";


    private CountDownLatch maximaStartedEvent = new CountDownLatch(1);

    private CommandExec maximaProccess;
    private File internalDir;
    private File externalDir;

    private final InteractionHistory interactionHistory;

    private MaximaBinder binder = new MaximaBinder();

    public class InteractionHistory {
        private final TreeMap<Integer, InteractionCell> cells = new TreeMap<>();

        public Collection<InteractionCell> allEntries() {
            return cells.values();
        }

        public int getCount() {
            return cells.size();
        }

        public InteractionCell getCell(final int i) {
            return cells.get(i);
        }

        public InteractionCell addCell(final String input, final String output, final InteractionCell.OutputType outputType, final boolean isNotice, final String outputLabel) {
            final int identifier = cells.size();
            final InteractionCell cell = new InteractionCell(identifier, input, output, outputType, isNotice, outputLabel);
            cells.put(identifier, cell);
            return cell;
        }

        public void removeCell(final int identifier) {
            cells.remove(identifier);
        }

        public void clear() {
            cells.clear();
        }
    }


    public class MaximaBinder extends Binder {
        public InteractionHistory getInteractionHistory() {
            return interactionHistory;
        }

        public InteractionCell performCommand(final String cmd) {
            return processCommand(cmd);
        }

        public String getOutput(final String outputLabel) {
            return getMaximaOutput(outputLabel);
        }

        public void quit() {
            exitMOA();
        }
    }

    public MaximaService() {
        super();

        interactionHistory = new InteractionHistory();
    }

    // NB Client activity should issue startService before binding

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        LogUtils.d("MaximaService.onStartCommand: started");
        super.onStartCommand(intent, flags, startId);

        if (maximaProccess == null) {

            internalDir = getFilesDir();
            externalDir = getExternalFilesDir(null);

            final Intent notificationIntent = new Intent(this, MaximaOnAndroidActivity.class);
            final PendingIntent pendingIntent =
                    PendingIntent.getActivity(this, 0, notificationIntent, 0);

            final NotificationCompat.Builder b =
                new NotificationCompat.Builder(this)
                    .setContentTitle("Maxima")
                    .setSmallIcon(R.drawable.moalogo)
                    .setContentIntent(pendingIntent)
                    .setTicker("Maxima");
            //.setContentText(getText(R.string.notification_message))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                b.setPriority(PRIORITY_LOW);
            }
            final Notification notification = b.build();
            startForeground(ONGOING_NOTIFICATION_ID, notification);

            startMaximaProcess();
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        if (maximaProccess == null) {
            startMaximaProcess();
        }
        try {
            maximaStartedEvent.await();
        } catch (InterruptedException e) {
            LogUtils.d("onBind: failed to wait for maxima start event: " + e);
            return null;
        }

        return binder;
    }

    private void startMaximaProcess() {
        LogUtils.d("MaximaService.startMaximaProcess: started");

        CpuArchitecture.initCpuArchitecture();

        final List<String> maximaCmd = new ArrayList<>();
        maximaCmd.add(internalDir + "/" + CpuArchitecture.getMaximaExecutableName());
        maximaCmd.add("--init-lisp=" + internalDir + "/init.lisp");

        try {
            String cmdStr = "";
            for (String part : maximaCmd) {
                cmdStr = cmdStr + " " + part;
            }
            LogUtils.d("MaximaService.startMaximaProcess: executing command" + cmdStr);
            maximaProccess = CommandExec.execute(maximaCmd);
        } catch (IOException e) {
            LogUtils.d("Exception while initialising maxima process: " + e);
            exitMOA();
        }
        maximaProccess.clearOutputBuffer();
        maximaStartedEvent.countDown();
        LogUtils.d("MaximaService.startMaximaProcess: done");
    }

    private void exitMOA() {
        try {
            maximaProccess.sendMaximaInput("quit();\n");
        } catch (Exception e) {
            LogUtils.d("Failed to send  quit() to Maxima: " + e);
            e.printStackTrace();
        }
        stopSelf();
    }


    // Get maxima's gnuplot graph specification that should be passed to the gnuplot executable.
    // Maxima names this output file using it's PID, so don't simplify this function too much.
    private String gnuplotInputFile() {
        return APP_DATA_TMP_DIR + "/maxout" + maximaProccess.getPID() + ".gnuplot";
    }

    private void prepareTmpDir() {
        final File tmpDir = new File(APP_DATA_TMP_DIR);
        if (tmpDir.isFile()) {
            tmpDir.delete();
            tmpDir.mkdir();
        } else if (tmpDir.isDirectory()) {
            for (final File f : tmpDir.listFiles()) {
                f.delete();
            }
        } else {
            tmpDir.mkdir();
        }
    }

    private void ensureMaximaStarted() {
        try {
            maximaStartedEvent.await();
        } catch (InterruptedException e) {
            LogUtils.d("ensureMaximaStarted: failed to wait for maxima start event: " + e);
        }
    }

    private InteractionCell processCommand(final String commandRaw) {
        ensureMaximaStarted();

        final String command = addCommandSemicolonIfNeeded(commandRaw);
        prepareTmpDir();

        try {
            LogUtils.d("Sending command " + command);
            maximaProccess.sendMaximaInput(command + "\n");
        } catch (IOException e) {
            LogUtils.d("Maxima threw IOException: " + e);
            e.printStackTrace();
            exitMOA();
        } catch (Exception e) {
            LogUtils.d("Maxima threw some other Exception: " + e);
            e.printStackTrace();
            exitMOA();
        }

        InteractionCell.OutputType maximaOutType = InteractionCell.OutputType.OutputText;
        String maximaResult = "";
        {
            String maxOut = maximaProccess.getMaximaOutput();
            Pair<Boolean, String> p = isStartQepcadString(maxOut);
            if (p.first) {
                final StringBuffer sb = new StringBuffer();
                while (p.first) {
                    sb.append(p.second);
                    final String qepcadExe =
                            QEPCAD_DIR + "/bin/qepcad" +
                                    (CpuArchitecture.getCpuArchitecture().equals(CpuArchitecture.Arch.x86) ? ".x86" : "");
                    final List<String> qepcadCmd = new ArrayList<>();
                    qepcadCmd.add(QEPCAD_DIR + "/qepcad.sh");
                    qepcadCmd.add(qepcadExe);
                    try {
                        CommandExec.executeAndWait(qepcadCmd);
                    } catch (Exception e) {
                        LogUtils.d("Exception while executing qepcad: " + e);
                        notifyUser("Failed to execute qepcad:\n" + e);
                    }
                    try {
                        maximaProccess.sendMaximaInput("qepcad finished\n");
                    } catch (IOException e) {
                        LogUtils.d("Exception when reporting to maxima that qepcad finished: " + e);
                    }
                    maxOut = maximaProccess.getMaximaOutput();
                    p = isStartQepcadString(maxOut);
                }
                sb.append(maxOut);
                maximaResult = sb.toString();
            } else {
                maximaResult = maxOut;
            }
        }
        LogUtils.d("Got result: " + maximaResult);

        LogUtils.d("processCommand: file " + gnuplotInputFile() + " exists: " + FileUtils.exists(gnuplotInputFile()));
        if (FileUtils.exists(gnuplotInputFile())) {
            final List<String> gnuplotCmd = new ArrayList<String>();
            final String gnuplotExe =
                    internalDir + "/additions/gnuplot/bin/gnuplot" +
                            (CpuArchitecture.getCpuArchitecture().equals(CpuArchitecture.Arch.x86) ? ".x86" : "") ;
            gnuplotCmd.add(gnuplotExe);
            gnuplotCmd.add(gnuplotInputFile());
            try {
                LogUtils.d("processCommand: starting gnuplot command " + gnuplotCmd);
                CommandExec.executeAndWait(gnuplotCmd);
            } catch (Exception e) {
                LogUtils.d("processCommand: failed to execute gnuplot: " + e);
                notifyUser("Failed to execute gnuplot:\n" + e);
            }
            final File gnuplotOut = new File(GNUPLOT_OUT);
            if (FileUtils.exists(gnuplotOut)) {
                maximaOutType = InteractionCell.OutputType.OutputSvg;
                try {
                    maximaResult = FileUtils.readFile(gnuplotOut);
                } catch (IOException e) {
                    LogUtils.d("processCommand: gnuplot graph does not exist: " + e);
                    notifyUser("Fatal error: gnuplot graph existed a moment ago but now doesn't");
                }
            }
        }

        String outputLabel = "";
        final String cleanedResult;
        {
            final Collection<String> lines = stripPromptLine(Arrays.asList(maximaResult.split("\n")));
            final Collection<String> filtered = new ArrayList<>();

            // Strip prompts
            for (final String line : lines) {
                final String line2 = line.trim();
                LogUtils.d("line2: " + line2);
                if (line2.startsWith("[[[[") && line2.endsWith("]]]]")) {
                    outputLabel = line2.substring(4, line2.length() - 4);
                } else if (line2.startsWith("(%i") && line2.endsWith(")")) {
                    // skip
                } else {
                    filtered.add(line);
                }
            }

            cleanedResult = joinLines(filtered); //.replace("\\\\", "\\");
        }
        LogUtils.d("Output label: '" + outputLabel + "'");
        // LogUtils.d("Cleaned result: " + cleanedResult);

        return interactionHistory.addCell(commandRaw, cleanedResult, maximaOutType, false, outputLabel);
    }

    private static String joinLines(final Collection<String> lines) {
        boolean firstLine = true;
        final StringBuilder result = new StringBuilder();
        for (final String line : lines) {
            if (firstLine) {
                firstLine = false;
            } else {
                result.append("\n");
            }
            result.append(line);
        }
        return result.toString();
    }

    private Collection<String> stripPromptLine(final Collection<String> input) {
        final List<String> result = new ArrayList<>();
        for (final String line : input) {
            final String line2 = line.trim();
            if (line2.startsWith("(%i") && line2.endsWith(")")) {
                // skip prompt
            } else {
                result.add(line);
            }
        }
        return result;
    }

    private Collection<String> stripEmptyLines(Collection<String> input) {
        final List<String> result = new ArrayList<>();
        for (final String line : input) {
            final String line2 = line.trim();
            if (line.trim().isEmpty()) {
                // skip prompt
            } else {
                result.add(line);
            }
        }
        return result;
    }

    private String getMaximaOutput(final String outputLabel) {
        if (outputLabel == null) {
            return null;
        }
        ensureMaximaStarted();

        final String command = ":lisp ($printf nil \"~A\" " + outputLabel + ")";
        try {
            LogUtils.d("getMaximaOutput: sending command: " + command);
            maximaProccess.sendMaximaInput(command + "\n");
        } catch (IOException e) {
            LogUtils.d("Maxima threw IOException: " + e);
            e.printStackTrace();
            exitMOA();
        } catch (Exception e) {
            LogUtils.d("Maxima threw some other Exception: " + e);
            e.printStackTrace();
            exitMOA();
        }

        final String rawOut = maximaProccess.getMaximaOutput();
        final String output =
            joinLines(
                stripEmptyLines(
                    stripPromptLine(
                        Arrays.asList(
                            rawOut.replace("\\'", "'").split("\n")))));
        LogUtils.d("getMaximaOutput: got: " + output);
        return output;
    }

    private Pair<Boolean, String> isStartQepcadString(final String res) {
        final int i = res.indexOf("start qepcad");
        if (i < 0) {
            return new Pair<>(false, "");
        } else if (i == 0) {
            return new Pair<>(true, "");
        } else {
            // i>0
            return new Pair<>(true, res.substring(0, i));
        }
    }

    private String addCommandSemicolonIfNeeded(final String cmd) {
        final String tmp = cmd.trim();
        return (tmp.endsWith(";") || tmp.endsWith("$")) ? tmp : tmp + ';';
    }

    private void notifyUser(final String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

}
