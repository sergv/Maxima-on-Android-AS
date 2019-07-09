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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.helpers.Util;

public class HTMLActivity extends AppCompatActivity {
	public String urlonCreate = null;
	WebView webview = null;

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.htmlactivity);
		Util.dimUIWith(findViewById(R.id.htmlactivity_toplevel));
		webview = (WebView) findViewById(R.id.webViewInHTMLActivity);
		final WebSettings settings = webview.getSettings();
		settings.setJavaScriptEnabled(true);
		webview.setWebViewClient(new WebViewClient() {
		});
		settings.setUseWideViewPort(true);
		settings.setLoadWithOverviewMode(true);

		settings.setBuiltInZoomControls(true);
		settings.setDefaultZoom(WebSettings.ZoomDensity.FAR);
		settings.setSupportZoom(true);

		// Disable Flash
		settings.setPluginState(WebSettings.PluginState.OFF);

		webview.addJavascriptInterface(this, "MOA");
		webview.setWebChromeClient(new WebChromeClient() {
			public boolean onConsoleMessage(ConsoleMessage cm) {
				Log.d("MyApplication",
						cm.message() + " -- From line " + cm.lineNumber()
								+ " of " + cm.sourceId());
				return true;
			}
		});

		if (Build.VERSION.SDK_INT >= 11) {
			final Intent intent = this.getIntent();
			final boolean hwaccel = intent.getBooleanExtra("hwaccel", true);
			if (!hwaccel) {
				webview.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null);
			}
		}

		loadURLonCreate();

	}

	@JavascriptInterface
	public void setFocus() {
		Log.d("MoA HTML", "setFocus is called");
		webview.post(new Runnable () {
			@Override
			public void run() {
				webview.requestFocus(View.FOCUS_DOWN);
				webview.loadUrl("javascript:textarea1Focus();");
			}
			});
	}

	private void loadURLonCreate() {
		final Intent origIntent = this.getIntent();
		final String urlonCreate = origIntent.getStringExtra("url");
		webview.setContentDescription(urlonCreate);
		webview.loadUrl(urlonCreate);
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		if (event.getAction() == KeyEvent.ACTION_DOWN
				&& keyCode == KeyEvent.KEYCODE_BACK
				&& webview.canGoBack()) {
			webview.goBack();
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}
}
