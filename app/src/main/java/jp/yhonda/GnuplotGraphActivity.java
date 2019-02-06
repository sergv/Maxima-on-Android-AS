/*
    Copyright 2012, 2013, Yasuaki Honda (yasuaki.honda@gmail.com)
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

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.helpers.Util;
import android.helpers.permissions.PermissionProvider;
import android.helpers.permissions.PermissionRequestsManager;
import android.helpers.permissions.SinglePermissionProvider;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGImageView;
import com.caverock.androidsvg.SVGParseException;

import java.io.File;
import java.io.IOException;

public class GnuplotGraphActivity extends AppCompatActivity {

	private static final int SAVE_GRAPH_RESULT_CODE = 0;

	private static double ZOOM_FACTOR = 0.25;

	private ZoomControls zoomControls;
	private String graphSvgContents;

	private final PermissionRequestsManager mPermissionManager;

	public GnuplotGraphActivity() {
		this.mPermissionManager = new PermissionRequestsManager(this);
	}

	@Override
	public void onRequestPermissionsResult(final int requestCode, final String[] permissions, final int[] grantResults) {
		mPermissionManager.onPermissionResult(requestCode, permissions, grantResults);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.gnuplot_graph_activity);
		Util.dimUIWith(findViewById(R.id.gnuplot_graph_view));

		final Intent origIntent = getIntent();
		final String graphFile = origIntent.getStringExtra("graph");

		final String svgContents;
		if (graphFile == null) {
			svgContents = origIntent.getStringExtra("graph-inline");
		} else {
			try {
				svgContents = FileUtils.readFile(new File(graphFile));
			} catch (IOException e) {
				Log.d("MoA", "Failed to read graph file: " + e);
				Toast.makeText(this, "Failed to read graph file " + graphFile, Toast.LENGTH_LONG).show();
				return;
			}
		}

		final SVGImageView graphView = (SVGImageView) findViewById(R.id.gnuplot_graph_view);

		try {
			final SVG img = SVG.getFromString(svgContents);
			graphView.setSVG(img);
			graphSvgContents = svgContents;
			zoomControls = new ZoomControls(this, graphView, (int) img.getDocumentWidth(), (int) img.getDocumentHeight());
		} catch (SVGParseException e) {
			Log.d("MoA", "Attempting to display invalid svg: " + e);
			Toast.makeText(this, "Plot inspector was supplied invalid svg!", Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.gnuplot_graph_activity_menu, menu);
		return true;
	}

	public void onMenuZoomIn(final MenuItem item) {
		zoomControls.zoom(1 + ZOOM_FACTOR);
	}

	public void onMenuZoomOut(final MenuItem item) {
		zoomControls.zoom(1 / (1 + ZOOM_FACTOR));
	}

	public void onMenuFitToScreen(final MenuItem item) {
		zoomControls.fitToScreen();
	}

	public void onMenuResetPositionAndZoom(final MenuItem item) {
		zoomControls.resetZoomAndPosition();
	}

	public void onMenuSaveGraphToFile(final MenuItem item) {
		//final Intent intentShareFile = new Intent(Intent.ACTION_SEND);
		if (graphSvgContents != null) {
			final Intent intent = new Intent(Intent.ACTION_PICK);
			intent.setAction("org.openintents.action.PICK_FILE");
			intent.putExtra(Intent.EXTRA_TITLE,"Save SVG graph to");
			intent.putExtra("org.openintents.extra.TITLE", "Save SVG graph to");
			startActivityForResult(intent, SAVE_GRAPH_RESULT_CODE);
		} else {
			Toast.makeText(this, "No graph available yet", Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		switch (requestCode) {
			case SAVE_GRAPH_RESULT_CODE: {
				if (resultCode == RESULT_OK && data != null && data.getData() != null && data.getData().getPath() != null) {
					final File dest = new File(data.getData().getPath());
					final Activity act = this;
					SinglePermissionProvider.requestPermission(
							R.string.write_graph_to_external_storage_rationale,
							mPermissionManager,
							Manifest.permission.WRITE_EXTERNAL_STORAGE,
							new SinglePermissionProvider.OnPermissionGranted() {
								@Override
								public void onPermissionResult(final String permission, final PermissionProvider.PermissionStatus grantResult) {
									switch (grantResult) {
									case Granted:
										try {
											if (!dest.exists()) {
												dest.createNewFile();
											}
											FileUtils.writeFile(dest, graphSvgContents);
											Toast.makeText(act, "Graph " + dest + " saved", Toast.LENGTH_SHORT).show();
										} catch (IOException e) {
											Log.d("MoA", "failed to write graph to " + dest + ": " + e);
											Toast.makeText(act, "Failed to copy graph to " + dest.getAbsolutePath(), Toast.LENGTH_LONG).show();
										}
										break;
									case Denied:
										Toast.makeText(act, R.string.write_graph_to_external_storage_failed_due_to_missing_permissions, Toast.LENGTH_LONG).show();
										break;
									}
								}
							});
				}
				break;
			}
		}
	}
}
