package jp.yhonda;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.helpers.Util;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
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
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaximaActivity extends AppCompatActivity {

    private static final String manURL = "file:///android_asset/maxima-doc/en/maxima.html";

    private static final MaximaVersion mvers = new MaximaVersion(5, 41, 0);
    public static final String TAG = "MoA";

    private File internalDir;
    private File externalDir;

    private static final int READ_REQUEST_CODE = 42;
    // File to be deleted on exit.
    private File temporaryScriptFile = null;
    private final double scriptFileMaxSize = 100 * 1024 * 1024;

    private final String maximaURL = Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN
            ? "file:///android_asset/maxima_svg.html"
            : "file:///android_asset/maxima_html.html";

    private MultiAutoCompleteTextView inputArea;
	// private WebView webview;

    private InteractionAdapter interactionHistoryAdapter;
    private ListView interactionHistoryView;
    private CountDownLatch binderInitialised = new CountDownLatch(1);
    private MaximaService.MaximaBinder serviceBinder;

    private String[] mcmdArray;
    private int mcmdArrayIndex;

    private class InteractionAdapter extends BaseAdapter {

        private final MaximaService.InteractionHistory cells;

        public InteractionAdapter(final MaximaService.InteractionHistory cells) {
            this.cells = cells;
        }

        @Override
        public int getCount() {
            return cells.getCount();
        }

        @Override
        public InteractionCell getItem(int i) {
            return cells.getCell(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(final int i, final View view, final ViewGroup viewGroup) {
            final InteractionCell cell = cells.getCell(i);
            View res = null;
            switch (cell.outputType) {
                case OutputText: {
                    TextView inputView = null;
                    TextView outputView = null;
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

                    inputView.setText(cell.isNotice ? cell.input : "> " + cell.input);
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

                    inputView.setText("> " + cell.input);
                    try {
                        outputView.setSVG(SVG.getFromString(cell.output));
                        outputView.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View view) {
                                showInlineGraph(cell.output);
                                return true;
                            }
                        });
                    } catch (SVGParseException e) {
                        Log.d(TAG, "Invalid svg graph:\n" + e.toString());
                    }
                    res = cellView;
                }
                    break;
            }
            return res;
        }

        public void removeCell(final int i) {
            cells.removeCell(i);
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

        internalDir = getFilesDir();
        externalDir = getExternalFilesDir(null);

        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

        inputArea = (MultiAutoCompleteTextView) findViewById(R.id.maxima_input);
        inputArea.setTextSize((float) Integer.parseInt(settings.getString("fontSize1", "20")));
        final Boolean bflag = settings.getBoolean("auto_completion_check_box_pref", true);
        inputArea.setThreshold(bflag ? 2 : 100);

        inputArea.setTokenizer(new MaximaTokenizer());
        inputArea.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.MaximaCompletionList)));

        final Activity activity = this;

        interactionHistoryView = (ListView) findViewById(R.id.interaction_history);
        interactionHistoryView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @SuppressWarnings({"unchecked"})
            @Override
            public void onItemClick(AdapterView<?> adapterView,
                                    View view,
                                    int position,
                                    long l) {
                final InteractionCell cell = ((AdapterView<InteractionAdapter>) adapterView).getAdapter().getItem(position);
                if (cell.isNotice) {
                    Toast.makeText(activity, "Not reusing notice", Toast.LENGTH_LONG).show();
                } else {
                    inputArea.setText(cell.input);
                }
            }
        });

        registerForContextMenu(interactionHistoryView);

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

        /*
        webview = new WebView(this);
        webview.getSettings().setJavaScriptEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        float sc = settings.getFloat("maxima main scale", 1.5f);
        webview.setInitialScale((int) (100 * sc));
		webview.getSettings().setBuiltInZoomControls(true);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
            webview.getSettings().setDisplayZoomControls(false);
        }
        */


        /*
        final StringBuilder buf = new StringBuilder();
        final InputStream json = getAssets().open("book/contents.json");
        final BufferedReader in =
                new BufferedReader(new InputStreamReader(json, "UTF-8"));
        String str;

        while ((str = in.readLine()) != null) {
            buf.append(str);
        }

        in.close();

        webview.loadData(encodedHtml, "text/html", null);
        */

        if (maximaRequiresInstall()) {
            final Intent intent = new Intent(this, MOAInstallerActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("version", mvers.versionString());
            this.startActivityForResult(intent, 0);
        } else {
            startMaximaProcess();
        }
    }

    @Override
    public void onCreateContextMenu(final ContextMenu menu, final View view, final ContextMenu.ContextMenuInfo contextMenuInfo) {

        super.onCreateContextMenu(menu, view, contextMenuInfo);

        final AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) contextMenuInfo;
        Assert.assertNotNull(info);

        final InteractionCell cell = interactionHistoryAdapter.getItem(info.position);
        if (cell.isNotice) {
            return;
        }

        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.maxima_interaction_cell_item_context_menu, menu);

        switch (cell.outputType) {
            case OutputText:
                menu.removeItem(R.id.maxima_context_menu_explore_graph);
                break;
            case OutputSvg:
                break;
        }
    }

    @Override
    public boolean onContextItemSelected(final MenuItem item) {
        final AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Assert.assertNotNull(info);
        final InteractionCell cell = interactionHistoryAdapter.getItem(info.position);

        switch (item.getItemId()) {
            case R.id.maxima_context_menu_reuse_input:
                if (cell.isNotice) {
                    Toast.makeText(this, "Not reusing notice", Toast.LENGTH_LONG).show();
                } else {
                    inputArea.setText(cell.input);
                }
                return true;
            case R.id.maxima_context_menu_reuse_output:
                if (cell.isNotice) {
                    Toast.makeText(this, "Not reusing notice", Toast.LENGTH_LONG).show();
                } else {
                    final String out = getOutput(cell.outputLabel);
                    if (out == null) {
                        Toast.makeText(this, "No output to reuse", Toast.LENGTH_LONG).show();
                    } else {
                        inputArea.setText(out);
                    }
                }
                return true;
            case R.id.maxima_context_menu_copy_input:
                if (cell.isNotice) {
                    Toast.makeText(this, "Not reusing notice", Toast.LENGTH_LONG).show();
                } else {
                    copyToClipboard(cell.input);
                }
                return true;
            case R.id.maxima_context_menu_copy_output:
                if (cell.isNotice) {
                    Toast.makeText(this, "Not reusing notice", Toast.LENGTH_LONG).show();
                } else {
                    final String out = getOutput(cell.outputLabel);
                    if (out == null) {
                        Toast.makeText(this, "No output to copy", Toast.LENGTH_LONG).show();
                    } else {
                        copyToClipboard(out);
                    }
                }
                return true;
            case R.id.maxima_context_menu_explore_graph:
                showInlineGraph(cell.output);
                return true;
            case R.id.maxima_context_menu_delete:
                if (cell.isNotice) {
                    Toast.makeText(this, "Not removing notice", Toast.LENGTH_LONG).show();
                    return true;
                }

                final DialogInterface.OnClickListener on_ok =
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface iface, final int which) {
                                interactionHistoryAdapter.removeCell(info.position);
                            }
                        };

                final String msg;
                switch (cell.outputType) {
                    case OutputText:
                        msg = String.format("Do you want to remove cell\n%s\n%s", cell.input, cell.output);
                        break;
                    case OutputSvg:
                        msg = String.format("Do you want to remove cell\n%s", cell.input);
                        break;
                    default:
                        msg = "Impossible";
                }
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Confirm history item removal")
                        .setCancelable(true)
                        .setIcon(android.R.drawable.stat_sys_warning)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, on_ok)
                        .setNegativeButton(android.R.string.cancel, null);
                builder.show();
                return true;
        }
        return super.onContextItemSelected(item);
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
        if (!(FileUtils.exists(internalDir + "/maxima-" + mvers.versionString())) &&
                !(FileUtils.exists(externalDir + "/maxima-" + mvers.versionString()))) {
            Toast.makeText(this, "Maxima executable does not exist, terminating", Toast.LENGTH_LONG).show();
            finish();
        }
        final Intent intent = new Intent(this, MaximaService.class);
        startService(intent);

        final Context cxt = this;
        final ServiceConnection conn = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder binder) {
                serviceBinder = (MaximaService.MaximaBinder) binder;

                interactionHistoryAdapter = new InteractionAdapter(serviceBinder.getInteractionHistory());
                //interactionHistoryAdapter.addCell(
                //    new InteractionCell(initialHeadline, firstMessage, InteractionCell.OutputType.OutputText));
                interactionHistoryView.setAdapter(interactionHistoryAdapter);

                binderInitialised.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                serviceBinder = null;
                Log.d(TAG, "Service disconnected for no apparent reason");
                Toast.makeText(cxt, "Maxima service disconnected for no reason!", Toast.LENGTH_LONG).show();
            }
        };
        bindService(intent, conn, BIND_AUTO_CREATE);
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
                showGraph(MaximaService.GNUPLOT_OUT);
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
            case R.id.maxima_main_menu_nextexample:
                copyExampleInputToInputArea();
                break;
            case R.id.maxima_main_menu_save:
                processCommand("ssave();");
                retval = true;
                break;
            case R.id.maxima_main_menu_restore:
                processCommand("srestore();");
                retval = true;
                break;
            case R.id.maxima_main_menu_playback:
                processCommand("playback();");
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

    private void processCommand(final String commandRaw) {
        final String command = commandRaw.trim();
        if (command.isEmpty()) {
            return;
        }

        try {
            binderInitialised.await();
        } catch (InterruptedException e) {
            Log.d(TAG, "Failed to await for binder initialisation" + e);
            Toast.makeText(this, "Failed to wait for maxima service", Toast.LENGTH_LONG).show();
            return;
        }
        final InteractionCell result = serviceBinder.performCommand(commandRaw);

        // interactionHistoryAdapter.addCell(result); // Already done by service
        interactionHistoryAdapter.notifyDataSetChanged();
        interactionHistoryView.smoothScrollToPosition(interactionHistoryAdapter.getCount() - 1);

        // Delete temporary script file:
        if (temporaryScriptFile != null) {
            temporaryScriptFile.delete();
        }
    }

    private void displayMaximaCmdResults(final InteractionCell.OutputType typ, final String input, final String output) {
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
            res.append(contents.replace("\n", "}\\\\\\\\ \\\\mbox{"));
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

    private void showHTML(final String url, final boolean hwaccel) {
        final Intent intent = new Intent(this, HTMLActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("url", url);
        intent.putExtra("hwaccel", hwaccel);
        startActivity(intent);
    }

    private void showPreference() {
        final Intent intent = new Intent(this, MoAPreferenceActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        startActivity(intent);
    }

    private void showManual() {
        final Intent intent = new Intent(this, ManualActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("url", manURL);
        intent.putExtra("manLangChanged", false);
        startActivityForResult(intent, 0);
    }

    private void showGraph(final String path) {
        Log.d(TAG, "showGraph: started");
        Log.d(TAG, "showGraph: graph file " + path + " exists: " + FileUtils.exists(path));
        if (FileUtils.exists(path)) {
            final Intent intent = new Intent(this, GnuplotGraphActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("graph", path);
            startActivity(intent);
        } else {
            Toast.makeText(this, getString(R.string.toast_no_graph), Toast.LENGTH_LONG).show();
        }
    }

    private void showInlineGraph(final String svg) {
        Log.d(TAG, "showInlineGraph: started");
        final Intent intent = new Intent(this, GnuplotGraphActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("graph-inline", svg);
        startActivity(intent);
    }

    private void exitMOA() {
        if (serviceBinder != null) {
            serviceBinder.quit();
        }
        finish();
    }

    private boolean maximaBinaryExists() {
        CpuArchitecture.initCpuArchitecture();
        final String res = CpuArchitecture.getMaximaExecutableName();
        return FileUtils.exists(internalDir.getAbsolutePath() + "/" + res);
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            Toast.makeText(this, getString(R.string.quit_hint), Toast.LENGTH_LONG).show();
        }
        return super.dispatchKeyEvent(event);
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
        Log.v(TAG, "onActivityResult()");
        // super.onActivityResult(requestCode, resultCode, data);
        final String sender = data != null ? data.getStringExtra("sender") : null;
        Log.v(TAG, "sender = " + sender);
        if (sender == null) {
            if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
                //User has selected a script file to load.
                final Uri uri = data.getData();
                //Copy file contents to input area:
                copyScriptFileToInputArea(uri);
            }
        } else if (sender.equals("manualActivity")) {
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
        Log.d(TAG, "copyExample()");
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
        mcmdArray = new String[j];
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
        mcmdArrayIndex = 0;
        copyExampleInputToInputArea();
    }

    private void copyExampleInputToInputArea() {
        if (mcmdArray == null) {
            Toast.makeText(this, "No examples selected", Toast.LENGTH_LONG).show();
            return;
        }
        Log.d(TAG, "copyExampleInputToInputArea()");
        inputArea.setText(mcmdArray[mcmdArrayIndex]);
        // inputArea.setSelection(mcmdArray[0].length());
        inputArea.requestFocus();
        mcmdArrayIndex++;
        if (mcmdArrayIndex == mcmdArray.length) {
            mcmdArrayIndex = 0;
            Toast.makeText(this, "All examples finished", Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(final String str) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            final ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboardManager != null) {
                final ClipData clip = android.content.ClipData.newPlainText("text", str);
                if (clip != null) {
                    clipboardManager.setPrimaryClip(clip);
                }
            }
        }
    }

    private String getOutput(final String outputLabel) {
        try {
            binderInitialised.await();
        } catch (InterruptedException e) {
            Log.d(TAG, "Failed to await for binder initialisation" + e);
            Toast.makeText(this, "Failed to wait for maxima service", Toast.LENGTH_LONG).show();
            return null;
        }
        return serviceBinder.getOutput(outputLabel);
    }

}
