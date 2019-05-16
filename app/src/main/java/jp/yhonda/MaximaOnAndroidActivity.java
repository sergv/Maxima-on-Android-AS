/*
    Copyright 2012, 2013, 2014, 2015, 2016, 2017 Yasuaki Honda (yasuaki.honda@gmail.com)
    This file is part of MaximaOnAndroid.

    MaximaOnAndroid is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    MaximaOnAndroid is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with MaximaOnAndroid.  If not, see <http://www.gnu.org/licenses/>.
 */

package jp.yhonda;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.MultiAutoCompleteTextView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

import android.helpers.Util;

import junit.framework.Assert;

public class MaximaOnAndroidActivity extends AppCompatActivity implements
		TextView.OnEditorActionListener, OnTouchListener {
	boolean initialised = false; /* expSize initialize is done or not */
	String[] mcmdArray = null; /* manual example input will be stored. */
	int mcmdArrayIndex = 0;

	private final String maximaURL = Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN
			? "file:///android_asset/maxima_svg.html"
			: "file:///android_asset/maxima_html.html";

	private static final String APP_DATA_DIR = "/data/data/jp.yhonda";

	private static final String QEPCAD_DIR   = APP_DATA_DIR + "/files/additions/qepcad";

	private static final String APP_DATA_TMP_DIR = APP_DATA_DIR + "/files/tmp";
	private static final String GNUPLOT_OUT      = APP_DATA_TMP_DIR + "/maxout.svg";
	private static final String QUEPCAD_INPUT    = APP_DATA_TMP_DIR + "/qepcad_input.txt";

	private static final String manjp = "file:///android_asset/maxima-doc/ja/maxima.html";
	private static final String manen = "file:///android_asset/maxima-doc/en/maxima.html";
	private static final String mande = "file:///android_asset/maxima-doc/en/de/maxima.html";
	private static final String manes = "file:///android_asset/maxima-doc/en/es/maxima.html";
	private static final String manpt = "file:///android_asset/maxima-doc/en/pt/maxima.html";
	private static final String manptbr = "file:///android_asset/maxima-doc/en/pt_BR/maxima.html";

	String manURL;
	boolean manLangChanged = true;
	boolean allExamplesFinished = false;
	final Semaphore maximaProcessStartedSem = new Semaphore(1);

	MultiAutoCompleteTextView inputArea;
	WebView webview;
	ScrollView scview;

	CommandExec maximaProccess;
	File internalDir;
	File externalDir;
	final MaximaVersion mvers = new MaximaVersion(5, 41, 0);

	private static final int READ_REQUEST_CODE = 42;
	// File to be deleted on exit.
    File temporaryScriptFile = null;
    final double scriptFileMaxSize = 1E7;

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		Log.d("MoA", "onCreate()");
		super.onCreate(savedInstanceState);

		PreferenceManager.setDefaultValues(this, R.xml.preference, false);
		final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
		manURL = pref.getString("manURL", manen);

		setContentView(R.layout.main);
		Util.dimUIWith(findViewById(R.id.maxima_on_android_toplevel));

		internalDir = this.getFilesDir();
		externalDir = this.getExternalFilesDir(null);

		webview = (WebView) findViewById(R.id.webView1);
		webview.getSettings().setJavaScriptEnabled(true);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			WebView.setWebContentsDebuggingEnabled(true);
		}
		final Activity thisActivity = this;
		webview.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(final WebView view, final String url) {
				Log.v("MoA", "onPageFinished");
				final SharedPreferences settings = PreferenceManager
						.getDefaultSharedPreferences(thisActivity);
				final float sc = settings.getFloat("maxima main scale", 1.5f);
				Log.v("MoA", "sc=" + Float.toString(sc));
				view.setInitialScale((int) (100 * sc));
			}

		});
		final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		float sc = settings.getFloat("maxima main scale", 1.5f);
		Log.v("MoA", "onCreate sc=" + Float.toString(sc));
		webview.setInitialScale((int) (100 * sc));

		webview.getSettings().setBuiltInZoomControls(true);
		if (Build.VERSION.SDK_INT > 11) {
			webview.getSettings().setDisplayZoomControls(false);
		}

		webview.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onConsoleMessage(final ConsoleMessage cm) {
				Log.d("MyApplication",
						cm.message() + " -- From line " + cm.lineNumber()
								+ " of " + cm.sourceId());
				return true;
			}
		});
		scview = (ScrollView) findViewById(R.id.scrollView1);

		webview.addJavascriptInterface(this, "MOA");

		inputArea = (MultiAutoCompleteTextView) findViewById(R.id.editText1);
		inputArea.setTextSize((float) Integer.parseInt(settings.getString("fontSize1", "20")));
		final Boolean bflag = settings.getBoolean("auto_completion_check_box_pref", true);
		inputArea.setThreshold(bflag ? 2 : 100);
		inputArea.setOnEditorActionListener(this);

		final ArrayAdapter<String> completionAdapter = new ArrayAdapter<>(
				this,
				android.R.layout.simple_dropdown_item_1line,
				getResources().getStringArray(R.array.MaximaCompletionList));
		inputArea.setTokenizer(new MaximaTokenizer());
		inputArea.setAdapter(completionAdapter);
		webview.setOnTouchListener(this);

		final Button enterB = (Button) findViewById(R.id.enterB);
		enterB.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				inputArea.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
				inputArea.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
			}
		});

		final MaximaVersion prevVers = new MaximaVersion();
		prevVers.loadVersFromSharedPrefs(this);
		final long verNo = prevVers.versionInteger();
		final long thisVerNo = mvers.versionInteger();

		if ((thisVerNo > verNo)
				|| !maximaBinaryExists()
				|| !FileUtils.exists(internalDir + "/additions")
				|| !FileUtils.exists(internalDir + "/init.lisp")
				|| (!FileUtils.exists(internalDir + "/maxima-" + mvers.versionString()) &&
					!FileUtils.exists(externalDir + "/maxima-" + mvers.versionString()))) {
			final Intent intent = new Intent(this, MOAInstallerActivity.class);
			intent.setAction(Intent.ACTION_VIEW);
			intent.putExtra("version", mvers.versionString());
			this.startActivityForResult(intent, 0);
		} else {
			// startMaxima();
			new Thread(new Runnable() {
				@Override
				public void run() {
					startMaxima();
				}
			}).start();
		}
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		Log.v("MoA", "onActivityResult()");
		// super.onActivityResult(requestCode, resultCode, data);
		final String sender = data != null ? data.getStringExtra("sender") : "anon";
		Log.v("MoA", "sender = " + sender);
        if (sender == null && requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            //User has selected a script file to load.
            final Uri uri = data.getData();
            //Copy file contents to input area:
			copyScriptFileToInputArea(uri);
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
				/* everything is installed properly. */
                mvers.saveVersToSharedPrefs(this);
                // startMaxima();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        startMaxima();
                    }
					}).start();

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

	@Override
	protected void onResume() {
		Log.d("MoA","onResume");
		super.onResume();
		final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		final AppGlobals globals = AppGlobals.getSingleton();
		String flag;

		flag = globals.get("auto_completion_check_box_pref");
		if (flag != null && flag.equals("true")) {
			final Boolean bflag = sharedPrefs.getBoolean("auto_completion_check_box_pref", true);
			inputArea.setThreshold(bflag ? 2 : 100);
        }

		flag = globals.get("manURL");
		if (flag != null && flag.equals("true")) {
			manLangChanged = true;
			manURL = sharedPrefs.getString("manURL",manen);
		} else {
			manLangChanged = false;
		}

		flag = globals.get("fontSize1");
		if (flag != null && flag.equals("true")) {
			inputArea.setTextSize((float) Integer.parseInt(sharedPrefs.getString("fontSize1", "20")));
		}

		flag = globals.get("fontSize2");
		if (flag != null && flag.equals("true")) {
			webview.loadUrl("javascript:window.ChangeExpSize(" + sharedPrefs.getString("fontSize2", "20") + ")");
		}

		final String list[] = { "auto_completion_check_box_pref", "manURL", "fontSize1", "fontSize2" };
		for (final String key : list) {
			globals.set(key, "false");
		}
	}

	private void startMaxima() {
		Log.d("MoA", "startMaxima()");
		try {
			maximaProcessStartedSem.acquire();
		} catch (InterruptedException e1) {
			Log.d("MoA", "exception1");
			e1.printStackTrace();
		}

		CpuArchitecture.initCpuArchitecture();

		runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl(maximaURL);}});
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
		maximaProcessStartedSem.release();
		Log.v("MoA", "maximaProcessStartedSem released.");
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
		mcmdArray = new String[j];
		j = 0;
		for (int i = 0; i < mcmd2.length; i++) {
			if (mcmd2[i].startsWith("i")) {
				int a = mcmd2[i].indexOf(" ");
				mcmdArray[j] = mcmd2[i].substring(a + 1);
				a = mcmdArray[j].lastIndexOf(";");
				if (a > 0) {
					mcmdArray[j] = mcmdArray[j].substring(0, a + 1); /*
																	 * to
																	 * include ;
																	 */
				} else {
					a = mcmdArray[j].lastIndexOf("$");
					if (a > 0) {
						mcmdArray[j] = mcmdArray[j].substring(0, a + 1); /*
																		 * to
																		 * include
																		 * $
																		 */
					}
				}
				j++;
			}
		}
		mcmdArrayIndex = 0;
		copyExampleInputToInputArea();
	}

	private void copyExampleInputToInputArea() {
		if (mcmdArray == null)
			return;
		Log.d("MoA", "copyExampleInputToInputArea()");
		inputArea.setText(mcmdArray[mcmdArrayIndex]);
		inputArea.setSelection(mcmdArray[mcmdArrayIndex].length());
		inputArea.requestFocus();
		mcmdArrayIndex++;
		if (mcmdArrayIndex == mcmdArray.length) {
			allExamplesFinished = true;
			mcmdArrayIndex = 0;
		}
	}

	@JavascriptInterface
	public void reuseByTouch(final String maximacmd) {
		final String text = substitute(maximacmd, "<br>", "");
		inputArea.post(new Runnable () {
			@Override
			public void run() {
				inputArea.setText(text);
			}
			});
	}

	@JavascriptInterface
	public void reuseOutput(final String oNumStr) {
		if (oNumStr.equals("nolabel")) {
			return;
		}
		final String olabel = "$%" + oNumStr;
		final String cmdstr = ":lisp ($printf nil \"$$$$$$ R0 ~a $$$$$$\" " + olabel + ")";
		try {
			maximaProccess.sendMaximaInput(cmdstr + "\n");
		} catch (IOException e) {
			Log.d("MoA", "reuseOutput exception1");
			e.printStackTrace();
			exitMOA();
		} catch (Exception e) {
			Log.d("MoA", "reuseOutput exception2");
			e.printStackTrace();
			exitMOA();
		}
		final String resString = maximaProccess.getMaximaOutput();
		final Pattern pa = Pattern.compile(".*\\$\\$\\$\\$\\$\\$ R0 (.+) \\$\\$\\$\\$\\$\\$.*");
		final Matcher ma = pa.matcher(resString);
		if (ma.find()) {
			final String oText = ma.group(1).replace("\\'","'");
			runOnUiThread(new Runnable() {@Override public void run() { inputArea.setText(oText); }});
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
				final ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
				final android.content.ClipData clip = android.content.ClipData.newPlainText("text", oText);
				clipboardManager.setPrimaryClip(clip);
			}
		}
	}

	@JavascriptInterface
	public void scrollToEnd() {
		final Handler handler = new Handler();
		final Runnable task = new Runnable() {

			@Override
			public void run() {
				final Runnable viewtask = new Runnable() {
					@Override
					public void run() {
						scview.fullScroll(ScrollView.FOCUS_DOWN);
						Log.v("MoA", "scroll!");
					}
				};
				scview.post(viewtask);
			}
		};
		handler.postDelayed(task, 1000);
	}

	@Override
	public boolean onEditorAction(final TextView testview, final int id, final KeyEvent keyEvent) {
		try {
			Log.v("MoA", "onEditorAction");
			maximaProcessStartedSem.acquire();
		} catch (InterruptedException e1) {
			Log.d("MoA", "exception3");
			e1.printStackTrace();
			exitMOA();
		}
		maximaProcessStartedSem.release();
		if (!initialised) {
			final SharedPreferences settings =
				PreferenceManager.getDefaultSharedPreferences(this);
			final String newsize = settings.getString("fontSize2", "");
			if (!newsize.trim().isEmpty()) {
				runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl("javascript:window.ChangeExpSize(" + newsize + ")");}});
			}
			initialised = true;
		}

		Log.v("MoA", "maximaProcessStartedSem released");
		String cmdstr = inputArea.getText().toString().trim();
		if ((keyEvent == null) || (keyEvent.getAction() == KeyEvent.ACTION_UP)) {
			try {
				// cmdstr = inputArea.getText().toString();
				if (cmdstr.isEmpty()) {
					return true;
				}
				if (cmdstr.equals("reload;")) {
					webview.loadUrl(maximaURL);
					return true;
				}
				if (cmdstr.equals("sc;")) {
					this.scrollToEnd();
					return true;
				}
				if (cmdstr.equals("quit();"))
					exitMOA();
				if (cmdstr.equals("aboutme;")) {
					showHTML("file:///android_asset/docs/aboutMOA.html", true);
					return true;
				}
				if (cmdstr.equals("man;")) {
					showHTML("file://" + internalDir + "/additions/en/maxima.html", true);
					return true;
				}
				if (cmdstr.equals("manj;")) {
					showHTML("file://" + internalDir + "/additions/ja/maxima.html", true);
					return true;
				}

				prepareTmpDir();
				cmdstr = maxima_syntax_check(cmdstr);
				Log.d("MoA", "Sending command " + cmdstr);
				maximaProccess.sendMaximaInput(cmdstr + "\n");
			} catch (IOException e) {
				Log.d("MoA", "exception4");
				e.printStackTrace();
				exitMOA();
			} catch (Exception e) {
				Log.d("MoA", "exception5");
				e.printStackTrace();
				exitMOA();
			}

			final String cmdstr2 = cmdstr;
			runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl("javascript:window.UpdateInput('"
					+ escapeChars(cmdstr2) + "<br>" + "')");}});
			String resString = maximaProccess.getMaximaOutput();
			while (isStartQepcadString(resString)) {
				final String qepcadExe =
					QEPCAD_DIR + "/bin/qepcad" +
					(CpuArchitecture.getCpuArchitecture().equals(CpuArchitecture.Arch.x86) ? ".x86" : "") ;
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
				resString = maximaProccess.getMaximaOutput();
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
					Log.d("MoA", "onEditorAction: starting gnuplot command " + gnuplotCmd);
					new CommandExec(gnuplotCmd);
					Log.d("MoA", "onEditorAction: started gnuplot command");
				} catch (IOException e) {
					Log.d("MoA", "exception6");
				}
				Log.d("MoA", "onEditorAction: file " + GNUPLOT_OUT + " exists: " + FileUtils.exists(GNUPLOT_OUT));
				if (FileUtils.exists(GNUPLOT_OUT)) {
					showGraph(GNUPLOT_OUT);
				}
			}

			displayMaximaCmdResults(resString);

		}

		return true;
	}

	private boolean isStartQepcadString(final String res) {
		final int i = res.indexOf("start qepcad");
		if (i < 0) {
			return false;
		} else if (i == 0) {
			return true;
		} else {
			// i>0
			displayMaximaCmdResults(res.substring(0, i));
			return true;
		}

	}

	private String maxima_syntax_check(String cmd) {
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



	private void displayMaximaCmdResults(final String resString) {
		Log.v("MoA cmd", resString);
		final String[] resArray = resString.split("\\$\\$\\$\\$\\$\\$");
		for (int i = 0; i < resArray.length; i++) {
			if (i % 2 == 0) {
				/* normal text, as we are outside of $$$$$$...$$$$$$ */
				if (resArray[i].trim().isEmpty()) {
					continue;
				}
				final String htmlStr = substitute(resArray[i], "\n", "<br>");
				runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl("javascript:window.UpdateText('" + htmlStr + "')");}});
			} else {
				/* tex commands, as we are inside of $$$$$$...$$$$$$ */
				final String texStr =
					substituteMBOXVERB(substitute(substCRinMBOX(resArray[i]), "\n", " \\\\\\\\ "));
				final String urlstr = "javascript:window.UpdateMath('" + texStr + "')";
				runOnUiThread(new Runnable() {@Override public void run() {webview.loadUrl(urlstr);}});
			}
		}
		if (allExamplesFinished) {
			Toast.makeText(this, "All examples are executed.", Toast.LENGTH_LONG).show();
			allExamplesFinished = false;
		}
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
			m.appendReplacement(sb, "\\\\\\\\text{$1");
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
		intent.putExtra("manLangChanged", manLangChanged);
		manLangChanged = false;
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

	@Override
	public boolean onTouch(View arg0, MotionEvent arg1) {
		if ((arg0 == webview) && (arg1.getAction() == MotionEvent.ACTION_UP)) {
			Log.v("MoA", "onTouch on webview");
			@SuppressWarnings("deprecation")
			float sc = webview.getScale();
			Log.v("MoA", "sc=" + Float.toString(sc));
			final SharedPreferences settings = PreferenceManager
					.getDefaultSharedPreferences(this);
			final Editor editor = settings.edit();
			editor.putFloat("maxima main scale", sc);
			editor.apply();
			// setMCIAfontSize((int)(sc*12));
		}
		return false;
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
		case R.id.maxima_main_menu_nextexample:
			copyExampleInputToInputArea();
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
			final android.support.v7.app.AlertDialog.Builder builder = new android.support.v7.app.AlertDialog.Builder(MaximaOnAndroidActivity.this);
			final LayoutInflater inflater = this.getLayoutInflater();
			final View layout = inflater.inflate(R.layout.scriptpathalert, null);
			builder.setView(layout);
			final AutoCompleteTextView textView = (AutoCompleteTextView) layout.findViewById(R.id.scriptPathInput);
			builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, final int which) {
					dialog.dismiss();
					final String contents = textView.getText().toString();
					if (!contents.isEmpty()) {
						copyScriptFileToInputArea(Uri.fromFile(new File(contents)));
					}
				}
			});
			android.support.v7.app.AlertDialog dialog = builder.create();
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

        //Build maxima load command and write it to the inputArea:
        final String command = "batch(\"" + temporaryFile.getAbsolutePath() + "\");";
        inputArea.setText(command);
    }
}
