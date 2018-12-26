/*
    Copyright 2012, 2013 Yasuaki Honda (yasuaki.honda@gmail.com)
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
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */

package jp.yhonda;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

public final class MOAInstallerActivity extends AppCompatActivity {
	File installedDir;
	File internalDir;
	File externalDir;
	Button okB;
	Button cancelB;
	RadioButton intB;
	RadioButton extB;
	RadioGroup rgroup;
	TextView msg;
	long intStorageAvail;
	long extStorageAvail;
	Activity me;
	public Activity parent;
	String systembindir = "/system/bin/";

	private long internalFlashAvail() {
		StatFs fs = new StatFs(internalDir.getAbsolutePath());
		return (((long) (fs.getAvailableBlocks()))
				* ((long) (fs.getBlockSize())) / (1024L * 1024L));
	}

	private long externalFlashAvail() {
		if (externalDir == null) {
			return 0L;
		}
		StatFs fs = new StatFs(externalDir.getAbsolutePath());
		return (((long) (fs.getAvailableBlocks()))
				* ((long) (fs.getBlockSize())) / (1024L * 1024L));
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.moainstallerview);
		me = this;
		internalDir = this.getFilesDir();
		externalDir = this.getExternalFilesDir(null);
		okB = (Button) findViewById(R.id.button1);
		cancelB = (Button) findViewById(R.id.button2);
		okB.setOnClickListener(ok_or_cancel_listener);
		cancelB.setOnClickListener(ok_or_cancel_listener);
		intB = (RadioButton) findViewById(R.id.radioButton1);
		extB = (RadioButton) findViewById(R.id.radioButton2);
		rgroup = (RadioGroup) findViewById(R.id.radiogroup);
		msg = (TextView) findViewById(R.id.checkedTextView1);
		if ((new File("/system/xbin/chmod")).exists()) {
			// Support for CyanogenMod
			systembindir = "/system/xbin/";
		}

		removeMaximaFiles();

		intStorageAvail = Math.abs(internalFlashAvail() - 5);
		extStorageAvail = Math.abs(externalFlashAvail() - 5);
		intB.setText(intB.getText() + " (" + String.valueOf(intStorageAvail)
				+ "MB)");
		extB.setText(extB.getText() + " (" + String.valueOf(extStorageAvail)
				+ "MB)");

		long limitMaximaBinary = 32L;
		if (intStorageAvail < limitMaximaBinary) {
			intB.setEnabled(false);
			extB.setEnabled(false);
			okB.setEnabled(false);
			msg.setText(R.string.internal_storage_insufficient);
		} else {
			long limitAvail = 85L;
			if (intStorageAvail < limitAvail) {
				intB.setEnabled(false);
			}
			if (extStorageAvail < limitAvail) {
				extB.setEnabled(false);
			}
			if (intStorageAvail < limitAvail && extStorageAvail < limitAvail) {
				okB.setEnabled(false);
				msg.setText(R.string.storage_insufficient_for_maxima_data);
			}
			/* Set the default check of the radio buttons */
			if (intStorageAvail >= limitAvail) {
				rgroup.check(R.id.radioButton1);
			}
			if (extStorageAvail >= limitAvail) {
				rgroup.check(R.id.radioButton2);
			}
		}
	}

	final Button.OnClickListener ok_or_cancel_listener = new Button.OnClickListener() {
		@Override
		public void onClick(final View view) {
			if (view == okB) {
				if (rgroup.getCheckedRadioButtonId() == R.id.radioButton1) {
					installedDir = internalDir;
				} else if (rgroup.getCheckedRadioButtonId() == R.id.radioButton2) {
					installedDir = externalDir;
				}
				install(0); // at the UnzipAsyncTask, install(1), install(2) and install(3)
							// will be called.
			} else if (view == cancelB) {
				Log.v("tako", "Cancel pressed.");
				install(10);
			}

		}
	};

	public void install(int stage) {
		// Where to Install
		// maxima, init.lisp : internalDir
		// maxima-5.X.0 : installedDir
		Intent data = null;
		final Intent origIntent = this.getIntent();
		final String vers = origIntent.getStringExtra("version");
		try {
			switch (stage) {
			case 0: {
				UnzipAsyncTask uzt = new UnzipAsyncTask(this);
				uzt.setParams(this.getAssets().open("additions.zip"),
						internalDir.getAbsolutePath(), getString(R.string.install_additions),
						"Additions installed");
				uzt.execute(0);
				break;
			}
			case 1: {
				chmod744(internalDir.getAbsolutePath() + "/additions/gnuplot/bin/gnuplot");
				chmod744(internalDir.getAbsolutePath() + "/additions/gnuplot/bin/gnuplot.x86");
				chmod744(internalDir.getAbsolutePath() + "/additions/qepcad/bin/qepcad");
				chmod744(internalDir.getAbsolutePath() + "/additions/qepcad/bin/qepcad.x86");
				chmod744(internalDir.getAbsolutePath() + "/additions/qepcad/qepcad.sh");
				CpuArchitecture.initCpuArchitecture();
				if (CpuArchitecture.getCpuArchitecture() == null) {
					Log.v("MoA","Install of additions failed.");
					install(10);
					me.finish();
				}
				// Existence of file x86 is used in qepcad.sh
				if (CpuArchitecture.getCpuArchitecture().equals(CpuArchitecture.Arch.x86)) {
					final File x86File = new File(internalDir.getAbsolutePath()+"/x86");
					if (!x86File.exists()) {
						x86File.createNewFile();
					}
				}
				final String maximaFile = CpuArchitecture.getMaximaExecutableName();
				if (maximaFile == null) {
					Log.v("MoA","Install of additions failed.");
					install(10);
					me.finish();
				}
				final String initlispPath = internalDir.getAbsolutePath()
						+ "/init.lisp";
				final String firstLine = "(setq *maxima-dir* \""
						+ installedDir.getAbsolutePath() + "/maxima-" + vers
						+ "\")\n";
				copyFileFromAssetsToLocal("init.lisp", initlispPath, firstLine);
				Log.d("My Test", "Clicked!1.1");
				UnzipAsyncTask uzt = new UnzipAsyncTask(this);
				uzt.setParams(this.getAssets().open(maximaFile + ".zip"),
						internalDir.getAbsolutePath(), getString(R.string.install_maxima_binary),
						"maxima binary installed");
				uzt.execute(1);
				break;
			}
			case 2: {
				chmod744(internalDir.getAbsolutePath() + "/" + CpuArchitecture.getMaximaExecutableName());
				UnzipAsyncTask uzt = new UnzipAsyncTask(this);
				uzt.setParams(this.getAssets().open("maxima-" + vers + ".zip"),
						installedDir.getAbsolutePath(), getString(R.string.install_maxima_data),
						"maxima data installed");
				uzt.execute(2);
				break;
			}
			case 3: {
				data = new Intent();
				data.putExtra("sender", "MOAInstallerActivity");
				setResult(RESULT_OK, data);

				me.finish();
				break;
			}
			case 10: {// Error indicated
				data = new Intent();
				data.putExtra("sender", "MOAInstallerActivity");
				setResult(RESULT_CANCELED, data);

				me.finish();
				break;
			}
			default:
				break;
			}
		} catch (IOException e1) {
			Log.d("MoA", "exception8");
			e1.printStackTrace();
			me.finish();
		} catch (Exception e) {
			Log.d("MoA", "exception9");
			e.printStackTrace();
			me.finish();
		}
	}

	private void copyFileFromAssetsToLocal(final String src, final String dest, final String line)
			throws Exception {
		InputStream fileInputStream = getApplicationContext().getAssets().open(
				src);
		BufferedOutputStream buf = new BufferedOutputStream(
				new FileOutputStream(dest));
		int read;
		final byte[] buffer = new byte[4096 * 128];
		buf.write(line.getBytes());
		while ((read = fileInputStream.read(buffer)) > 0) {
			buf.write(buffer, 0, read);
		}
		buf.close();
		fileInputStream.close();
	}

	private void chmod744(final String filename) {
		final List<String> list = new ArrayList<String>();
		list.add(systembindir + "chmod");
		list.add("744");
		list.add(filename);
		final CommandExec sce = new CommandExec();
		try {
			sce.execCommand(list);
		} catch (IOException e) {
			Log.v("MoA","exception chmod744 1");
		} catch (Exception e) {
			Log.v("MoA","exception chmod744 2");
		}
	}

	private void removeMaximaFiles() {
		final MaximaVersion prevVers = new MaximaVersion();
		prevVers.loadVersFromSharedPrefs(this);
		final String maximaDirName = "/maxima-" + prevVers.versionString();
		final String maximaDirPath;
		if ((new File(internalDir.getAbsolutePath() + maximaDirName)).exists()) {
			maximaDirPath = internalDir.getAbsolutePath() + maximaDirName;
		} else if ((externalDir != null)
				&& (new File(externalDir.getAbsolutePath() + maximaDirName))
						.exists()) {
			maximaDirPath = externalDir.getAbsolutePath() + maximaDirName;
		} else {
			maximaDirPath = null;
		}
		final String filelist[] = { internalDir.getAbsolutePath() + "/init.lisp",
				internalDir.getAbsolutePath() + "/x86",
				internalDir.getAbsolutePath() + "/maxima",
				internalDir.getAbsolutePath() + "/maxima.x86",
				internalDir.getAbsolutePath() + "/maxima.pie",
				internalDir.getAbsolutePath() + "/maxima.x86.pie",
				internalDir.getAbsolutePath() + "/additions", maximaDirPath };
		for (int i = 0; i < filelist.length; i++) {
			if ((filelist[i] != null) && (new File(filelist[i])).exists()) {
				final List<String> list = new ArrayList<String>();
				list.add(systembindir + "rm");
				list.add("-R");
				list.add(filelist[i]);
				CommandExec sce = new CommandExec();
				try {
					sce.execCommand(list);
				} catch (IOException e) {
					Log.d("MoA", "exception10");
					e.printStackTrace();
				} catch (Exception e) {
					Log.d("MoA", "exception11");
					e.printStackTrace();
				}
			}
		}
	}

}
