package jp.yhonda;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.helpers.Util;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.MultiAutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGImageView;
import com.caverock.androidsvg.SVGParseException;

import junit.framework.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaximaActivity extends Activity {

    private static final String APP_DATA_DIR = "/data/data/jp.yhonda";

    private static final String QEPCAD_DIR   = APP_DATA_DIR + "/files/additions/qepcad";

    private static final String APP_DATA_TMP_DIR = APP_DATA_DIR + "/files/tmp";
    private static final String GNUPLOT_OUT      = APP_DATA_TMP_DIR + "/maxout.svg";
    private static final String QUEPCAD_INPUT    = APP_DATA_TMP_DIR + "/qepcad_input.txt";

    private static final String manURL = "file:///android_asset/maxima-doc/en/maxima.html";

    private CommandExec maximaProccess;
    private File internalDir;
    private File externalDir;
    private static final MaximaVersion mvers = new MaximaVersion(5, 41, 0);

    private static final int READ_REQUEST_CODE = 42;
    // File to be deleted on exit.
    private File temporaryScriptFile = null;
    private final double scriptFileMaxSize = 1E7;

    private final String maximaURL = Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN
            ? "file:///android_asset/maxima_svg.html"
            : "file:///android_asset/maxima_html.html";

    private static final String initialHeadline = "Maxima 5.41.0, ECL 16.1.3, MathJax 2.7.5, Gnuplot 5.2.4";
    private static final String firstMessage =
            // "Maxima on Android 3.2.1 September 7, 2018\n" +
            // "\n" +
            "\n" +
            "You can touch previous commands for reuse, like input history.\n" +
            "Long touch alows to reuse either input or output.\n" +
            // "You can touch manual examples to execute them in Maxima.\n" +
            "\n" +
            "Dedicated to the memory of William Schelter.";

    private MultiAutoCompleteTextView inputArea;

    private final Semaphore sem = new Semaphore(1);

    private InteractionAdapter interactionHistory;
    private ListView interactionHistoryView;

    public enum OutputType {
        OutputText, OutputSvg
    }

    public class InteractionCell {
        public final String input;
        public final String output;
        public final OutputType outputType;

        public InteractionCell(final String input, final String output, final OutputType outputType) {
            this.input      = input;
            this.output     = output;
            this.outputType = outputType;
        }
    }

    private class InteractionAdapter extends BaseAdapter {

        private final List<InteractionCell> cells = new ArrayList<>();

        @Override
        public int getCount() {
            return cells.size();
        }

        @Override
        public InteractionCell getItem(int i) {
            return cells.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(final int i, final View view, final ViewGroup viewGroup) {
            final InteractionCell cell = cells.get(i);
            View res = null;
            switch (cell.outputType) {
                case OutputText: {
                    TextView inputView = null, outputView = null;
                    final View cellView =
                            view != null &&
                            ((inputView = (TextView) view.findViewById(R.id.interaction_cell_text_input)) != null) &&
                            ((outputView = (TextView) view.findViewById(R.id.interaction_cell_text_output)) != null)
                            ? view
                            : LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.interaction_cell_text, viewGroup, false);

                    if (inputView == null) {
                        inputView = (TextView) cellView.findViewById(R.id.interaction_cell_text_input);
                    }
                    if (outputView == null) {
                        outputView = (TextView) cellView.findViewById(R.id.interaction_cell_text_output);
                    }

                    inputView.setText("> " + cell.input);
                    outputView.setText(cell.output);
                    res = cellView;
                }
                    break;
                case OutputSvg: {
                    TextView inputView = null;
                    SVGImageView outputView = null;
                    final View cellView =
                            view != null &&
                                    ((inputView  = (TextView)     view.findViewById(R.id.interaction_cell_graph_input)) != null) &&
                                    ((outputView = (SVGImageView) view.findViewById(R.id.interaction_cell_graph_output)) != null)
                                    ? view
                                    : LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.interaction_cell_graph, viewGroup, false);

                    if (inputView == null) {
                        inputView = (TextView) cellView.findViewById(R.id.interaction_cell_graph_input);
                    }
                    if (outputView == null) {
                        outputView = (SVGImageView) cellView.findViewById(R.id.interaction_cell_graph_output);
                    }

                    inputView.setText(cell.input);
                    try {
                        outputView.setSVG(SVG.getFromString(cell.output));
                    } catch (SVGParseException e) {
                        Log.d("MoA", "Invalid svg graph:\n" + e.toString());
                    }
                    res = cellView;
                }
                    break;
            }
            return res;
        }

        public void addCell(final InteractionCell cell) {
            cells.add(cell);
            notifyDataSetChanged();
        }

        public void removeCell(final int i) {
            cells.remove(i);
            notifyDataSetChanged();
        }

        public void clear() {
            cells.clear();
            notifyDataSetInvalidated();
        }
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.maxima_repl);
        Util.dimUIWith(findViewById(R.id.maxima_repl_toplevel));

        internalDir = this.getFilesDir();
        externalDir = this.getExternalFilesDir(null);

        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

        inputArea = (MultiAutoCompleteTextView) findViewById(R.id.maxima_input);
        inputArea.setTextSize((float) Integer.parseInt(settings.getString("fontSize1", "20")));
        final Boolean bflag = settings.getBoolean("auto_completion_check_box_pref", true);
        inputArea.setThreshold(bflag ? 2 : 100);

        inputArea.setTokenizer(new MaximaTokenizer());
        inputArea.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.MaximaCompletionList)));

        interactionHistory = new InteractionAdapter();
        interactionHistory.addCell(
            new InteractionCell(initialHeadline, firstMessage, OutputType.OutputText));
        interactionHistoryView = (ListView) findViewById(R.id.interaction_history);
        interactionHistoryView.setAdapter(interactionHistory);
        interactionHistoryView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @SuppressWarnings({"unchecked"})
            @Override
            public void onItemClick(AdapterView<?> adapterView,
                                    View view,
                                    int position,
                                    long l) {
                final InteractionCell cell = ((AdapterView<InteractionAdapter>) adapterView).getAdapter().getItem(position);
                inputArea.setText(cell.input);
            }
        });

        final Activity activity = this;
        final AdapterView.OnItemLongClickListener removeCell = new AdapterView.OnItemLongClickListener() {
            @SuppressWarnings({"unchecked"})
            @Override
            public boolean onItemLongClick(final AdapterView<?> adapterView,
                                           final View view,
                                           final int position,
                                           final long id) {
                final InteractionAdapter adapter = ((AdapterView<InteractionAdapter>) adapterView).getAdapter();
                final InteractionCell cell = adapter.getItem(position);

                final DialogInterface.OnClickListener on_ok =
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface iface, final int which) {
                                adapter.removeCell(position);
                            }
                        };

                final String msg = String.format("Do you want to remove cell\n%s\n%s",
                        cell.input,
                        cell.output);

                AlertDialog.Builder builder =
                        new AlertDialog.Builder(activity);
                builder.setTitle("Confirm history item removal")
                        .setCancelable(true)
                        .setIcon(android.R.drawable.stat_sys_warning)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, on_ok)
                        .setNegativeButton(android.R.string.cancel, null);
                builder.show();
                return true;
            }
        };
        interactionHistoryView.setOnItemLongClickListener(removeCell);


        final Button enter = (Button) findViewById(R.id.button_enter);
        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                processCommand(inputArea.getText().toString());
                inputArea.setText("");
            }
        });
        inputArea.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(final TextView v, final int id, final KeyEvent keyEvent) {
                if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_UP) {
                    processCommand(inputArea.getText().toString());
                    inputArea.setText("");
                }
                return true;
            }
        });

        if (maximaRequiresInstall()) {
            final Intent intent = new Intent(this, MOAInstallerActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("version", mvers.versionString());
            this.startActivityForResult(intent, 0);
        } else {
            startMaximaProcess();
            /*
            new Thread(new Runnable() {
                @Override
                public void run() {
                    startMaximaProcess();
                }
            }).start();
            */
        }
    }

    // Returns whether maxima installation is up to date
    private boolean maximaRequiresInstall() {
        final MaximaVersion prevVers = new MaximaVersion();
        prevVers.loadVersFromSharedPrefs(this);
        final long currentVersion = prevVers.versionInteger();
        final long desiredVersion = mvers.versionInteger();

        return ((desiredVersion > currentVersion)
                || !maximaBinaryExists()
                || !FileUtils.exists(internalDir + "/additions")
                || !FileUtils.exists(internalDir + "/init.lisp")
                || (!FileUtils.exists(internalDir + "/maxima-" + mvers.versionString()) &&
                !FileUtils.exists(externalDir + "/maxima-" + mvers.versionString())));
    }

    private void startMaximaProcess() {
        Log.d("MoA", "startMaximaProcess()");
        try {
            sem.acquire();
        } catch (InterruptedException e1) {
            Log.d("MoA", "exception1");
            e1.printStackTrace();
        }

        CpuArchitecture.initCpuArchitecture();

        if (!(FileUtils.exists(internalDir + "/maxima-" + mvers.versionString())) &&
                !(FileUtils.exists(externalDir + "/maxima-" + mvers.versionString()))) {
            this.finish();
        }
        final List<String> maximaCmd = new ArrayList<>();
        maximaCmd.add(internalDir + "/" + CpuArchitecture.getMaximaExecutableName());
        maximaCmd.add("--init-lisp=" + internalDir + "/init.lisp");

        try {
            maximaProccess = new CommandExec(maximaCmd);
        } catch (IOException e) {
            Log.d("MoA", "Exception while initialising maxima process: " + e);
            exitMOA();
        }
        maximaProccess.clearOutputBuffer();
        sem.release();
        Log.v("MoA", "sem released.");
    }

    public void processCommand(final String commandRaw) {
        try {
            Log.v("MoA", "processCommand");
            sem.acquire();
        } catch (InterruptedException e1) {
            Log.d("MoA", "failed to acquire semaphore");
            e1.printStackTrace();
            exitMOA();
        }
        sem.release();

        Log.v("MoA", "semaphore released");
        final String commandTrimmed = commandRaw.trim();
        if (commandTrimmed.isEmpty()) {
            return;
        }
        final String command = maxima_syntax_check(commandTrimmed);
        prepareTmpDir();

        try {
            Log.d("MoA", "Sending command " + command);
            maximaProccess.sendMaximaInput(command + "\n");
        } catch (IOException e) {
            Log.d("MoA", "Maxima threw IOException: " + e);
            e.printStackTrace();
            exitMOA();
        } catch (Exception e) {
            Log.d("MoA", "Maxima threw some other Exception: " + e);
            e.printStackTrace();
            exitMOA();
        }

        /*
        final String cmdstr2 = command;
        runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl("javascript:window.UpdateInput('"
                + escapeChars(cmdstr2) + "<br>" + "')");}});
        */

        OutputType maximaOutType = OutputType.OutputText;
        String maximaResult = "";
        {
            String maxOut = maximaProccess.getMaximaOutput();
            Pair<Boolean, String> p = isStartQepcadString(maxOut);
            if (p.first) {
                while (p.first) {
                    maximaResult += p.second;
                    final String qepcadExe =
                            QEPCAD_DIR + "/bin/qepcad" +
                                    (CpuArchitecture.getCpuArchitecture().equals(CpuArchitecture.Arch.x86) ? ".x86" : "");
                    final List<String> qepcadCmd = new ArrayList<>();
                    qepcadCmd.add(QEPCAD_DIR + "/qepcad.sh");
                    qepcadCmd.add(qepcadExe);
                    try {
                        new CommandExec(qepcadCmd);
                    } catch (IOException e) {
                        Log.d("MoA", "Exception while executing qepcad: " + e);
                    }
                    try {
                        maximaProccess.sendMaximaInput("qepcad finished\n");
                    } catch (IOException e) {
                        Log.d("MoA", "Exception when reporting to maxima that qepcad finished: " + e);
                    }
                    maxOut = maximaProccess.getMaximaOutput();
                    p = isStartQepcadString(maxOut);
                }
                maximaResult += maxOut;
            } else {
                maximaResult = maxOut;
            }
        }

        Log.d("MoA", "onEditorAction: file " + gnuplotInputFile() + " exists: " + FileUtils.exists(gnuplotInputFile()));
        if (FileUtils.exists(gnuplotInputFile())) {
            final List<String> gnuplotCmd = new ArrayList<String>();
            final String gnuplotExe =
                    internalDir + "/additions/gnuplot/bin/gnuplot" +
                            (CpuArchitecture.getCpuArchitecture().equals(CpuArchitecture.Arch.x86) ? ".x86" : "") ;
            gnuplotCmd.add(gnuplotExe);
            gnuplotCmd.add(gnuplotInputFile());
            try {
                Log.d("MoA", "processCommand: starting gnuplot command " + gnuplotCmd);
                new CommandExec(gnuplotCmd);
            } catch (IOException e) {
                Log.d("MoA", "processCommand: failed to execute gnuplot: " + e);
            }
            final File gnuplotOut = new File(GNUPLOT_OUT);
            if (FileUtils.exists(gnuplotOut)) {
                maximaOutType = OutputType.OutputSvg;
                try {
                    maximaResult = FileUtils.readFile(gnuplotOut);
                } catch (IOException e) {
                    Log.d("MoA", "processCommand: gnuplot graph does not exist: " + e);
                }
                showGraph(GNUPLOT_OUT);
            }
        }

        displayMaximaCmdResults(maximaOutType, commandRaw, maximaResult);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.maxima_main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        boolean retval = false;
        switch (item.getItemId()) {
            case R.id.maxima_main_menu_about:
                showHTML("file:///android_asset/About_MoA/index.html", true);
                retval = true;
                break;
            case R.id.maxima_main_menu_preference:
                showPreference();
                retval = true;
                break;
            case R.id.maxima_main_menu_graph:
                showGraph(GNUPLOT_OUT);
                retval = true;
                break;
            case R.id.maxima_main_menu_quit:
                exitMOA();
                retval = true;
                break;
            case R.id.maxima_main_menu_man:
                showManual();
                retval = true;
                break;
            case R.id.maxima_main_menu_save:
                sessionMenu("ssave();");
                retval = true;
                break;
            case R.id.maxima_main_menu_restore:
                sessionMenu("srestore();");
                retval = true;
                break;
            case R.id.maxima_main_menu_playback:
                sessionMenu("playback();");
                retval = true;
                break;
            case R.id.maxima_main_menu_loadscript:
                selectScriptFile();
                break;

            default:
                return super.onOptionsItemSelected(item);
        }
        return retval;
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

    private String maxima_syntax_check(final String cmd) {
        /*
         * Search the last char which is not white spaces. If the last char is
         * semi-colon or dollar, that is OK. Otherwise, semi-colon is added at
         * the end.
         */
        int i = cmd.length() - 1;
        Assert.assertTrue(i >= 0);
        char c = ';';
        while (i >= 0) {
            c = cmd.charAt(i);
            if (c == ' ' || c == '\t') {
                i--;
            } else {
                break;
            }
        }

        if (c == ';' || c == '$') {
            return cmd.substring(0, i + 1);
        } else {
            return cmd.substring(0, i + 1) + ';';
        }
    }

    private String escapeChars(String cmd) {
        return substitute(cmd, "'", "\\'");
    }

    private void displayMaximaCmdResults(final OutputType typ, final String input, final String output) {
        Log.v("MoA cmd", output);
        /*
        final String[] resArray = output.split("\\$\\$\\$\\$\\$\\$");
        for (int i = 0; i < resArray.length; i++) {
            if (i % 2 == 0) {
                // normal text, as we are outside of $$$$$$...$$$$$$
                if (resArray[i].trim().isEmpty()) {
                    continue;
                }
                final String htmlStr = substitute(resArray[i], "\n", "<br>");
                runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl("javascript:window.UpdateText('" + htmlStr + "')");}});
            } else {
                // tex commands, as we are inside of $$$$$$...$$$$$$
                String texStr = substCRinMBOX(resArray[i]);
                texStr = substitute(texStr, "\n", " \\\\\\\\ ");
                texStr = substituteMBOXVERB(texStr);
                final String urlstr = "javascript:window.UpdateMath('" + texStr + "')";
                runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl(urlstr);}});
            }
        }
        */
        final String[] lines = output.split("\n");
        StringBuilder filtered = new StringBuilder();
		// Strip prompts
        for (final String line : lines) {
            final String line2 = line.trim();
            if (line2.startsWith("(%i") && line2.endsWith(")")) {
                // skip prompt line
            } else {
                filtered.append(line);
                filtered.append("\n");
            }
        }
        interactionHistory.addCell(new InteractionCell(
                input,
                filtered.toString(),
                typ));
        interactionHistoryView.smoothScrollToPosition(interactionHistory.getCount() - 1);
        //Delete temporary script file:
        if (temporaryScriptFile != null) {
            temporaryScriptFile.delete();
        }
    }

    private String substCRinMBOX(final String str) {
        final StringBuilder res = new StringBuilder();
        String tmp = str;
        int startPos = -1;
        while ((startPos = tmp.indexOf("mbox{")) != -1) {
            res.append(tmp.substring(0, startPos));
            res.append("mbox{");
            final int contentStart = startPos + 5;
            final int contentEnd = tmp.indexOf("}", contentStart);
            Assert.assertTrue(contentEnd > 0);
            final String contents = tmp.substring(contentStart, contentEnd);
            res.append(substitute(contents, "\n", "}\\\\\\\\ \\\\mbox{"));
            tmp = tmp.substring(contentEnd, tmp.length());
        }
        res.append(tmp);
        return new String(res);
    }

    static private String substituteMBOXVERB(String texStr) {
        final Pattern pat = Pattern.compile("\\\\\\\\mbox\\{\\\\\\\\verb\\|(.)\\|\\}");
        final Matcher m = pat.matcher(texStr);
        final StringBuffer sb = new StringBuffer();
        if (m.find()) {
            m.appendReplacement(sb,"\\\\\\\\text{$1");
            while (m.find()) {
                m.appendReplacement(sb, "$1");
            }
            m.appendTail(sb);
            return sb.toString() + "}";
        }
        return texStr;
    }

    static private String substitute(final String input, final String pattern, final String replacement) {
        int index = input.indexOf(pattern);

        if (index == -1) {
            return input;
        }

        final StringBuffer buffer = new StringBuffer();

        buffer.append(input.substring(0, index)).append(replacement);

        if (index + pattern.length() < input.length()) {
            final String rest = input.substring(index + pattern.length(),
                    input.length());
            buffer.append(substitute(rest, pattern, replacement));
        }
        return buffer.toString();
    }

    private void showHTML(final String url, final boolean hwaccel) {
        final Intent intent = new Intent(this, HTMLActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("url", url);
        intent.putExtra("hwaccel", hwaccel);
        this.startActivity(intent);
    }

    private void showPreference() {
        final Intent intent = new Intent(this, MoAPreferenceActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        this.startActivity(intent);
    }

    private void showManual() {
        final Intent intent = new Intent(this, ManualActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("url", manURL);
        intent.putExtra("manLangChanged", false);
        this.startActivityForResult(intent, 0);
    }

    private void showGraph(final String path) {
        Log.d("MoA", "showGraph: started");
        Log.d("MoA", "showGraph: graph file " + path + " exists: " + FileUtils.exists(path));
        if (FileUtils.exists(path)) {
            final Intent intent = new Intent(this, GnuplotGraphActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("graph", path);
            this.startActivity(intent);
        } else {
            Toast.makeText(this, getString(R.string.toast_no_graph), Toast.LENGTH_LONG).show();
        }
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

    private void exitMOA() {
        try {
            maximaProccess.sendMaximaInput("quit();\n");
            finish();
        } catch (IOException e) {
            Log.d("MoA", "exception7");
            e.printStackTrace();
        } catch (Exception e) {
            Log.d("MoA", "exception8");
            e.printStackTrace();
        }
        finish();
    }

    private boolean maximaBinaryExists() {
        CpuArchitecture.initCpuArchitecture();
        final String res = CpuArchitecture.getMaximaExecutableName();
        return (new File(internalDir.getAbsolutePath() + "/" + res)).exists();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    Toast.makeText(this, getString(R.string.quit_hint), Toast.LENGTH_LONG)
                            .show();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void sessionMenu(String cmd) {
        inputArea.setText(cmd);
        inputArea.dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        inputArea.dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    private void selectScriptFile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            //Versions earlier than KitKat do not support the Storage Access Framework.
            //Show file path input box instead:
            final android.support.v7.app.AlertDialog.Builder builder = new android.support.v7.app.AlertDialog.Builder(MaximaActivity.this);
            final LayoutInflater inflater = this.getLayoutInflater();
            final View layout = inflater.inflate(R.layout.scriptpathalert, null);
            builder.setView(layout);
            final AutoCompleteTextView textView = (AutoCompleteTextView) layout.findViewById(R.id.scriptPathInput);
            builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int which) {
                    dialog.dismiss();
                    final String input = textView.getText().toString();
                    if (!input.isEmpty()) {
                        copyScriptFileToInputArea(Uri.fromFile(new File(input)));
                    }
                }
            });
            final android.support.v7.app.AlertDialog dialog = builder.create();
            dialog.show();

        } else {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, READ_REQUEST_CODE);
        }
    }

    private void copyScriptFileToInputArea(final Uri fileUri) {
        //Copy script file contents into a temporary file:
        final File temporaryDirectory = getApplicationContext().getCacheDir();
        File temporaryFile = null;
        try {
            final ParcelFileDescriptor parcelFileDescriptor = getContentResolver().openFileDescriptor(fileUri, "r");
            final FileInputStream fileInputStream = new FileInputStream(parcelFileDescriptor.getFileDescriptor());
            final FileChannel sourceChannel = fileInputStream.getChannel();

            //Abort if file is too large:
            if (sourceChannel.size() > scriptFileMaxSize) {
                Toast.makeText(getApplicationContext(), R.string.script_file_too_large, Toast.LENGTH_LONG).show();
                return;
            }

            temporaryFile = File.createTempFile("userscript", ".mac", temporaryDirectory);
            final FileChannel destinationChannel = new FileOutputStream(temporaryFile).getChannel();
            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        } catch (Exception e) {
            e.printStackTrace();
            if (temporaryFile != null) {
                temporaryFile.delete();
            }
            Toast.makeText(getApplicationContext(), R.string.error_loading_script_file, Toast.LENGTH_LONG).show();
            return;
        }
        temporaryScriptFile = temporaryFile;   //Store temp file for later deletion

        //Build maxima load command and write it to the editText:
        final String command = "batch(\"" + temporaryFile.getAbsolutePath() + "\");";
        inputArea.setText(command);
    }


    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        Log.v("MoA", "onActivityResult()");
        // super.onActivityResult(requestCode, resultCode, data);
        final String sender = data != null ? data.getStringExtra("sender") : null;
        Log.v("MoA", "sender = " + sender);
        if (sender == null) {
            if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
                //User has selected a script file to load.
                final Uri uri = data.getData();
                //Copy file contents to input area:
                copyScriptFileToInputArea(uri);
            }
        } else if (sender != null && sender.equals("manualActivity")) {
            if (resultCode == RESULT_OK) {
                final String mcmd = data.getStringExtra("maxima command");
                if (mcmd != null) {
                    copyExample(mcmd);
                }
            }
        } else if (sender.equals("FBActivity")) {
            if (resultCode == RESULT_OK) {
                final String mcmd = data.getStringExtra("maxima command");
                if (mcmd != null) {
                    inputArea.setText(mcmd);
                    inputArea.setSelection(mcmd.length());
                    inputArea.requestFocus();
                }
            }
        } else if (sender.equals("MOAInstallerActivity")) {
            if (resultCode == RESULT_OK) {
                /* Everything is installed properly. */
                mvers.saveVersToSharedPrefs(this);
                startMaximaProcess();
            } else {
                final DialogInterface.OnClickListener finish = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        finish();
                    }
                };
                new AlertDialog.Builder(this)
                        .setTitle(R.string.installer_title)
                        .setMessage(R.string.install_failure)
                        .setPositiveButton(R.string.OK, finish)
                        .show();
            }
        }
    }

    private void copyExample(final String mcmd) {
        Log.d("MoA", "copyExample()");
        String[] mcmd2 = mcmd.split("\n\\(%");
        mcmd2[0] = mcmd2[0].replaceFirst("^\\(%", "");
        for (int i = 0; i < mcmd2.length; i++) {
            mcmd2[i] = mcmd2[i].replaceAll("\n", " ");
        }

        int j = 0;
        for (int i = 0; i < mcmd2.length; i++) {
            if (mcmd2[i].startsWith("i")) {
                j++;
            }
        }
        final String[] mcmdArray = new String[j];
        j = 0;
        for (int i = 0; i < mcmd2.length; i++) {
            if (mcmd2[i].startsWith("i")) {
                int a = mcmd2[i].indexOf(" ");
                mcmdArray[j] = mcmd2[i].substring(a + 1);
                a = mcmdArray[j].lastIndexOf(";");
                if (a > 0) {
                    mcmdArray[j] = mcmdArray[j].substring(0, a + 1); // to include ;
                } else {
                    a = mcmdArray[j].lastIndexOf("$");
                    if (a > 0) {
                        mcmdArray[j] = mcmdArray[j].substring(0, a + 1); // to include $
                    }
                }
                j++;
            }
        }
        // mcmdArrayIndex = 0;
        copyExampleInputToInputArea(mcmdArray);
    }

    private void copyExampleInputToInputArea(final String [] mcmdArray) {
        if (mcmdArray == null) {
            return;
        }
        Log.d("MoA", "copyExampleInputToInputArea()");
        inputArea.setText(mcmdArray[0]);
        // inputArea.setSelection(mcmdArray[0].length());
        inputArea.requestFocus();
        // mcmdArrayIndex++;
        // if (mcmdArrayIndex == mcmdArray.length) {
        //     allExamplesFinished = true;
        //     mcmdArrayIndex = 0;
        // }
    }
}
