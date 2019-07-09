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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.MultiAutoCompleteTextView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.helpers.Util;

import junit.framework.Assert;

public class MaximaOnAndroidActivity extends AppCompatActivity {

	private static final String TAG = "MoA";

	private static final String AUTO_COMPLETION_CHECK_BOX_PREF = "auto completion check enabled";
	public static final String MAIN_SCALE_PREF = "main scale";
	public static final String BROWSER_TEXT_SIZE_PREF = "browser font size";
	public static final String INPUT_AREA_TEXT_SIZE_PREF = "input area font size";

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

	private String manURL;
	private boolean manLangChanged = true;
	private boolean allExamplesFinished = false;

    private CountDownLatch binderInitialised = new CountDownLatch(1);
    private CountDownLatch javascriptAvailable = new CountDownLatch(1);
    private MaximaService.MaximaBinder serviceBinder;
    private ServiceConnection conn;

	private MultiAutoCompleteTextView inputArea;
	private WebView webview;
	private ScrollView scview;

	private File internalDir;
	private File externalDir;
	private static final MaximaVersion mvers = new MaximaVersion(5, 41, 0);

	private static final int READ_REQUEST_CODE = 42;
	// File to be deleted on exit.
    private File temporaryScriptFileToCleanUp = null;

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
				Log.d("MoA", "onPageFinished");
				final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(thisActivity);
				final float sc = settings.getFloat(MAIN_SCALE_PREF, 1.5f);
				Log.d("MoA", "onPageFinished: scale = " + Float.toString(sc));
				view.setInitialScale((int) (100 * sc));
				signalJavascriptInitialised();
			}

		});
		final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		float sc = settings.getFloat(MAIN_SCALE_PREF, 1.5f);
		Log.d("MoA", "onCreate scale =" + Float.toString(sc));
		webview.setInitialScale((int) (100 * sc));

		webview.getSettings().setBuiltInZoomControls(true);
		if (Build.VERSION.SDK_INT > 11) {
			webview.getSettings().setDisplayZoomControls(false);
		}

		webview.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onConsoleMessage(final ConsoleMessage cm) {
				Log.d(TAG, cm.sourceId() + ":" + cm.lineNumber() + ":" + cm.message());
				return true;
			}
		});
		scview = (ScrollView) findViewById(R.id.scrollView1);

		webview.addJavascriptInterface(this, "MOA");

		inputArea = (MultiAutoCompleteTextView) findViewById(R.id.editText1);
		inputArea.setTextSize((float) Integer.parseInt(settings.getString(INPUT_AREA_TEXT_SIZE_PREF, "20")));
		final Boolean isEnableAutoCompletion = settings.getBoolean(AUTO_COMPLETION_CHECK_BOX_PREF, true);
		inputArea.setThreshold(isEnableAutoCompletion ? 2 : 100);
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

		final Button enterB = (Button) findViewById(R.id.enterB);
		enterB.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				processCommand(inputArea.getText().toString());
				inputArea.setText("");
			}
		});

		final ArrayAdapter<String> completionAdapter = new ArrayAdapter<>(
				this,
				android.R.layout.simple_dropdown_item_1line,
				getResources().getStringArray(R.array.MaximaCompletionList));
		inputArea.setTokenizer(new MaximaTokenizer());
		inputArea.setAdapter(completionAdapter);

		// TODO
		// webview.setOnTouchListener(new OnTouchListener() {
		// 	@Override
		// 	public boolean onTouch(final View view, final MotionEvent event) {
		// 		if ((view == webview) && (event.getAction() == MotionEvent.ACTION_UP)) {
		// 			Log.d("MoA", "onTouch on webview");
		// 			@SuppressWarnings("deprecation")
		// 			float sc = webview.getScale();
		// 			Log.d("MoA", "sc=" + Float.toString(sc));
		// 			final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		// 			final Editor editor = settings.edit();
		// 			editor.putFloat(MAIN_SCALE_PREF, sc);
		// 			editor.apply();
		// 			// setMCIAfontSize((int)(sc*12));
		// 		}
		// 		return false;
		// 	}
		// });

		final String newsize = settings.getString(BROWSER_TEXT_SIZE_PREF, "");
		if (!newsize.trim().isEmpty()) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					webview.loadUrl("javascript:window.ChangeExpSize(" + newsize + ")");
				}
			});
		}

		if (maximaRequiresInstall()) {
			final Intent intent = new Intent(this, MOAInstallerActivity.class);
			intent.setAction(Intent.ACTION_VIEW);
			intent.putExtra("version", mvers.versionString());
			this.startActivityForResult(intent, 0);
		} else {
            startMaximaProcess();
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

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		Log.d("MoA", "onActivityResult()");
		// super.onActivityResult(requestCode, resultCode, data);
		final String sender = data != null ? data.getStringExtra("sender") : "anon";
		Log.d("MoA", "sender = " + sender);
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

	@Override
	protected void onResume() {
		Log.d("MoA","onResume");
		super.onResume();
		final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		final AppGlobals globals = AppGlobals.getSingleton();
		String flag;

		flag = globals.get(AUTO_COMPLETION_CHECK_BOX_PREF);
		if (flag != null && flag.equals("true")) {
			final Boolean isEnableAutoCompletion = sharedPrefs.getBoolean(AUTO_COMPLETION_CHECK_BOX_PREF, true);
			inputArea.setThreshold(isEnableAutoCompletion ? 2 : 100);
        }

		flag = globals.get("manURL");
		if (flag != null && flag.equals("true")) {
			manLangChanged = true;
			manURL = sharedPrefs.getString("manURL",manen);
		} else {
			manLangChanged = false;
		}

		flag = globals.get(INPUT_AREA_TEXT_SIZE_PREF);
		if (flag != null && flag.equals("true")) {
			inputArea.setTextSize((float) Integer.parseInt(sharedPrefs.getString(INPUT_AREA_TEXT_SIZE_PREF, "20")));
		}

		flag = globals.get(BROWSER_TEXT_SIZE_PREF);
		if (flag != null && flag.equals("true")) {
			webview.loadUrl("javascript:window.ChangeExpSize(" + sharedPrefs.getString(BROWSER_TEXT_SIZE_PREF, "20") + ")");
		}

		final String list[] = { AUTO_COMPLETION_CHECK_BOX_PREF, "manURL", INPUT_AREA_TEXT_SIZE_PREF, BROWSER_TEXT_SIZE_PREF };
		for (final String key : list) {
			globals.set(key, "false");
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (conn != null) {
			unbindService(conn);
		}
	}

    private void startMaximaProcess() {
        Log.d(TAG, "MaximaOnAndroidActivity.startMaximaProcess: started");

        if (!(FileUtils.exists(internalDir + "/maxima-" + mvers.versionString())) &&
                !(FileUtils.exists(externalDir + "/maxima-" + mvers.versionString()))) {
            Toast.makeText(this, "Maxima executable does not exist, terminating", Toast.LENGTH_LONG).show();
            finish();
        }

        runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Log.d(TAG, "MaximaOnAndroidActivity.startMaximaProcess: load maximaURL " + maximaURL);
				webview.loadUrl(maximaURL);
			}
		});

        final Intent intent = new Intent(this, MaximaService.class);
        // Ensure the service will keep running even after current activity is done.
        startService(intent);

        final Context cxt = this;
        conn = new ServiceConnection() {

            @Override
            public void onServiceConnected(final ComponentName componentName, final IBinder binder) {
                Log.d(TAG, "MaximaOnAndroidActivity.startMaximaProcess.onServiceConnected: started");
                serviceBinder = (MaximaService.MaximaBinder) binder;

                restoreHistory(serviceBinder.getInteractionHistory());

                signalBinderInitialised();
                Log.d(TAG, "MaximaOnAndroidActivity.startMaximaProcess.onServiceConnected: done");
            }

            @Override
            public void onServiceDisconnected(final ComponentName componentName) {
                serviceBinder = null;
                Log.d(TAG, "Service disconnected for no apparent reason");
                Toast.makeText(cxt, "Maxima service disconnected for no reason!", Toast.LENGTH_LONG).show();
            }
        };
        bindService(intent, conn, BIND_AUTO_CREATE);
        Log.d(TAG, "MaximaOnAndroidActivity.startMaximaProcess: done");
    }

    private void restoreHistory(final MaximaService.InteractionHistory history) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!ensureJavascriptAvailable()) {
                    return;
                }

                for (final InteractionCell cell : history.allEntries()) {
                    displayMaximaCmdResults(cell);
                }
            }
        }).start();
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
			return;
        }
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

	private void showMenuForCell(final int identifier) {
		if (!ensureBinderInitialised()) {
			return;
		}

		final InteractionCell cell = serviceBinder.getInteractionHistory().getCell(identifier);

		final PopupMenu menu = new PopupMenu(this, inputArea);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			menu.setGravity(Gravity.CENTER_VERTICAL);
		}
		menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(final MenuItem item) {
				switch (item.getItemId()) {
					case R.id.maxima_context_menu_reuse_input:
						if (cell.isNotice) {
							Toast.makeText(MaximaOnAndroidActivity.this, "Not reusing notice", Toast.LENGTH_LONG).show();
						} else {
							inputArea.setText(cell.input);
						}
						return true;
					case R.id.maxima_context_menu_reuse_output:
						if (cell.isNotice) {
							Toast.makeText(MaximaOnAndroidActivity.this, "Not reusing notice", Toast.LENGTH_LONG).show();
						} else {
							final String out = getOutput(cell.outputLabel);
							if (out == null) {
								Toast.makeText(MaximaOnAndroidActivity.this, "No output to reuse", Toast.LENGTH_LONG).show();
							} else {
								inputArea.setText(out);
							}
						}
						return true;
					case R.id.maxima_context_menu_copy_input:
						if (cell.isNotice) {
							Toast.makeText(MaximaOnAndroidActivity.this, "Not reusing notice", Toast.LENGTH_LONG).show();
						} else {
							copyToClipboard(cell.input);
						}
						return true;
					case R.id.maxima_context_menu_copy_output:
						if (cell.isNotice) {
							Toast.makeText(MaximaOnAndroidActivity.this, "Not reusing notice", Toast.LENGTH_LONG).show();
						} else {
							final String out = getOutput(cell.outputLabel);
							if (out == null) {
								Toast.makeText(MaximaOnAndroidActivity.this, "No output to copy", Toast.LENGTH_LONG).show();
							} else {
								copyToClipboard(out);
							}
						}
						return true;
					case R.id.maxima_context_menu_explore_graph:
						showInlineGraph(cell.output);
						return true;
					case R.id.maxima_context_menu_delete:
						removeCell(cell);
						return true;
				}
				return false;
			}
		});

        switch (cell.outputType) {
            case OutputText:
                menu.inflate(R.menu.maxima_interaction_cell_item_text_context_menu);
                break;
            case OutputSvg:
                menu.inflate(R.menu.maxima_interaction_cell_item_svg_context_menu);
                break;
        }
        runOnUiThread(new Runnable() {
			@Override
			public void run() {
				menu.show();
			}
		});
	}

	private void removeCell(final InteractionCell cell) {

		if (cell.isNotice) {
			Toast.makeText(MaximaOnAndroidActivity.this, "Not removing notice", Toast.LENGTH_LONG).show();
			return;
		}

		final DialogInterface.OnClickListener on_ok = new DialogInterface.OnClickListener() {
			@Override
			public void onClick(final DialogInterface iface, final int which) {
				serviceBinder.getInteractionHistory().removeCell(cell.identifier);

				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						final String removeCellUrl = "javascript:window.RemoveCell(" + cell.identifier + ")";
						Log.d(TAG, "removeCellUrl: " + removeCellUrl);
						webview.loadUrl(removeCellUrl);
					}
				});

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
		final AlertDialog.Builder builder = new AlertDialog.Builder(MaximaOnAndroidActivity.this);
		builder.setTitle("Confirm history item removal")
				.setCancelable(true)
				.setIcon(android.R.drawable.stat_sys_warning)
				.setMessage(msg)
				.setPositiveButton(android.R.string.ok, on_ok)
				.setNegativeButton(android.R.string.cancel, null);
		builder.show();
	}

	@JavascriptInterface
	public void onTouchInput(final int identifier) {
        Log.d(TAG, "MaximaOnAndroidActivity.onTouchInput: identifier = " + identifier);
		showMenuForCell(identifier);
	}

	@JavascriptInterface
	public void onTouchOutput(final int identifier) {
        Log.d(TAG, "MaximaOnAndroidActivity.onTouchOutput: identifier = " + identifier);
		showMenuForCell(identifier);
	}

	@JavascriptInterface
	public void onTouchInlineGraph(final int identifier) {
        Log.d(TAG, "MaximaOnAndroidActivity.onTouchInlineGraph: identifier = " + identifier);

		if (!ensureBinderInitialised()) {
			return;
		}

		final InteractionCell cell = serviceBinder.getInteractionHistory().getCell(identifier);
		showInlineGraph(cell.output);
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
						Log.d("MoA", "scroll!");
					}
				};
				scview.post(viewtask);
			}
		};
		handler.postDelayed(task, 1000);
	}

    private void processCommand(final String commandRaw) {
        final String command = commandRaw.trim();
        final String commandNoSemicolon = command.endsWith(";") ? command.substring(0, command.length() - 1) : command;

		if (!ensureBinderInitialised()) {
			return;
		}

		if (commandNoSemicolon.isEmpty()) {
			return;
		} else if (commandNoSemicolon.equals("reload")) {
			webview.loadUrl(maximaURL);
			return;
		} else if (commandNoSemicolon.equals("sc")) {
			this.scrollToEnd();
			return;
		} else if (commandNoSemicolon.equals("quit()")) {
			serviceBinder.quit();
			return;
		} else if (commandNoSemicolon.equals("aboutme")) {
			showHTML("file:///android_asset/docs/aboutMOA.html", true);
			return;
		} else if (commandNoSemicolon.equals("man")) {
			showHTML("file://" + internalDir + "/additions/en/maxima.html", true);
			return;
		} else if (commandNoSemicolon.equals("manj")) {
			showHTML("file://" + internalDir + "/additions/ja/maxima.html", true);
			return;
		}

		final InteractionCell result = serviceBinder.performCommand(commandRaw);

		displayMaximaCmdResults(result);

        // Delete temporary script file:
        if (temporaryScriptFileToCleanUp != null) {
            temporaryScriptFileToCleanUp.delete();
        }
	}

	private void displayMaximaCmdResults(final InteractionCell result) {

		switch (result.outputType) {
			case OutputText: {

				final String resString = result.output;
				Log.d(TAG, "displayMaximaCmdResults: " + resString);
				final String[] resArray = resString.split("\\$\\$\\$\\$\\$\\$");
				final ArrayList<String> escapedResults = new ArrayList<>();
				for (int i = 0; i < resArray.length; i++) {

					/* normal text, as we are outside of $$$$$$...$$$$$$ */
					if (resArray[i].trim().isEmpty()) {
						continue;
					}

					if (i % 2 == 0) {
						final String html = substitute(resArray[i], "\n", "<br>");
						final String url = "javascript:window.UpdateOutputText(" + result.identifier + ", " + i + ", '" + html + "')";
						escapedResults.add(url);
					} else {
						/* tex commands, as we are inside of $$$$$$...$$$$$$ */
						final String tex =
							substituteMBOXVERB(substitute(substCRinMBOX(resArray[i]), "\n", " \\\\\\\\ "));
						final String url = "javascript:window.UpdateOutputMath(" + result.identifier + ", " + i + ", '" + tex + "')";
						escapedResults.add(url);
					}
				}

				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						final String updateInputUrl = "javascript:window.UpdateInput(" + result.identifier + ", '" + escapeChars("> " + result.input) + "')";
						Log.d(TAG, "updateInputUrl: " + updateInputUrl);
						webview.loadUrl(updateInputUrl);

						for (final String str : escapedResults) {
							Assert.assertNotNull(str);
							Log.d(TAG, "update result url: " + str);
							webview.loadUrl(str);
						}
					}
					});

				if (allExamplesFinished) {
					Toast.makeText(this, "All examples are executed.", Toast.LENGTH_LONG).show();
					allExamplesFinished = false;
				}
			}
			break;
			case OutputSvg: {
				final String svg = result.output;
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						final String updateInputUrl = "javascript:window.UpdateInput(" + result.identifier + ", '" + escapeChars("> " + result.input) + "')";
						Log.d(TAG, "updateInputUrl: " + updateInputUrl);
						webview.loadUrl(updateInputUrl);

						final String updateOutputGraphUrl = "javascript:window.UpdateOutputGraph(" + result.identifier + ", '" + escapeChars(svg) + "')";
						webview.loadUrl(updateOutputGraphUrl);
					}
				});
			}
			break;
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

	static private String substituteMBOXVERB(final String texStr) {
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

	static private String escapeChars(final String cmd) {
		return substitute(cmd, "'", "\\'");
	}

	static private String substitute(final String input, final String pattern, final String replacement) {
		return input.replace(pattern, replacement);
		// int index = input.indexOf(pattern);
        //
		// if (index == -1) {
		// 	return input;
		// }
        //
		// final StringBuffer buffer = new StringBuffer();
        //
		// buffer.append(input.substring(0, index)).append(replacement);
        //
		// if (index + pattern.length() < input.length()) {
		// 	final String rest = input.substring(index + pattern.length(), input.length());
		// 	buffer.append(substitute(rest, pattern, replacement));
		// }
		// return buffer.toString();
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

    private void showInlineGraph(final String svg) {
        Log.d(TAG, "showInlineGraph: started");
        final Intent intent = new Intent(this, GnuplotGraphActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("graph-inline", svg);
        startActivity(intent);
    }

	private boolean maximaBinaryExists() {
		CpuArchitecture.initCpuArchitecture();
		final String res = CpuArchitecture.getMaximaExecutableName();
		return (new File(internalDir.getAbsolutePath() + "/" + res)).exists();
	}

	@Override
	public boolean dispatchKeyEvent(final KeyEvent event) {
		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_BACK:
				Toast.makeText(this, getString(R.string.quit_hint), Toast.LENGTH_LONG).show();
				return true;
			}
		}
		return super.dispatchKeyEvent(event);
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
			if (!ensureBinderInitialised()){
				return false;
			}
			serviceBinder.quit();
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
        temporaryScriptFileToCleanUp = temporaryFile;   //Store temp file for later deletion

        //Build maxima load command and write it to the inputArea:
        final String command = "batch(\"" + temporaryFile.getAbsolutePath() + "\");";
        inputArea.setText(command);
    }

	private String getOutput(final String outputLabel) {
		if (!ensureBinderInitialised()) {
			return null;
		}
		return serviceBinder.getOutput(outputLabel);
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
		} else {
			Toast.makeText(this, "Copy to clipboard not supported on current Android version", Toast.LENGTH_LONG).show();
		}
	}


	private boolean ensureBinderInitialised() {
        try {
            binderInitialised.await();
			return true;
        } catch (InterruptedException e) {
            Log.d(TAG, "Failed to await for binder initialisation" + e);
            Toast.makeText(this, "Failed to wait for maxima service", Toast.LENGTH_LONG).show();
            return false;
        }
	}

	private void signalBinderInitialised() {
		binderInitialised.countDown();
	}

    private boolean ensureJavascriptAvailable() {
        try {
            javascriptAvailable.await();
            return true;
        } catch (InterruptedException e) {
            Log.d(TAG, "Failed to await for javascript initialisation" + e);
            Toast.makeText(this, "Failed to wait for page loading by the web view", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void signalJavascriptInitialised() {
        javascriptAvailable.countDown();
    }
}
