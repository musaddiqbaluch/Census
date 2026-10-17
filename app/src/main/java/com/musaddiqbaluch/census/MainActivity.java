package com.musaddiqbaluch.census;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.graphics.shapes.*;
import androidx.palette.*;
import com.airbnb.lottie.*;
import com.google.android.material.*;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener;
import com.materialkolor.colorutilities.*;
import com.musaddiqbaluch.census.databinding.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import androidx.graphics.shapes.Shapes_androidKt;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.color.MaterialColors;
import androidx.core.view.HapticFeedbackConstantsCompat;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
	
	private MainBinding binding;
	private InstalledAppTracker installedAppTracker;
	private AppCountTextAnimator appCountAnimator;
	private boolean activityResumed;
	private InstallerTabController installerTabController;
	private HapticFeedbackManager hapticManager;
	private MaterialToolbarScrollHelper toolbarScrollHelper;
	private MaterialDistributionBar materialDistributionBar;
	private RoundedShapeDrawable bodyOutlineDrawable;
	private RoundedShapeDrawable borderDrawable1;
	private RoundedShapeDrawable borderDrawable2;
	private UpdateManager.Handle updateHandle;
	private static final String DESTROY_TAG = "MainDestroy";
	private FloatingCirclesDrawable circlesBg;
	private GlassBackdropDrawable glassBg;
	private GlassBackdropDrawable glassLabel;
	private GlassBackdropDrawable glassAppCount;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = MainBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		
		
		// Skip the ripple when the Activity is being recreated
		// (theme switch, rotation, process restore). Fresh launches
		// have savedInstanceState == null and DO play it.
		if (_savedInstanceState == null) {
			final View rippleRoot = getWindow().getDecorView();
			rippleRoot.post(new Runnable() {
				@Override public void run() {
					if (rippleRoot.isAttachedToWindow()
					&& rippleRoot.isLaidOut()) {
						new LiquidRippleEffect(rippleRoot).play();
					}
				}
			});
		}
		
		// ... all your existing setup ...
	}
	
	private void initializeLogic() {
		// 1. App tracker
		installedAppTracker =
		new InstalledAppTracker(
		this,
		false
		);
		
		// 2. AppCountTextAnimator (numeric value only, never color)
		appCountAnimator =
		new AppCountTextAnimator(
		binding.appCountText
		);
		
		// 3. MaterialDistributionBar (owns every artwork-driven color).
		//    The third argument is REQUIRED.
		materialDistributionBar =
		new MaterialDistributionBar(
		binding.distributionBar,
		binding.appCountTextLayout,
		binding.appCountText
		);
		
		// 4. RoundedShapeDrawables behind the artwork pipeline
		RoundedShapeDrawable bodyOutlineDrawable =
		new RoundedShapeDrawable(binding.bodyOutline)
		.radius(28)
		.backgroundAttr(
		com.google.android.material.R.attr
		.colorSurfaceContainerLow
		)
		.stroke(1, 0.40f)
		.applyToView();
		
		RoundedShapeDrawable borderDrawable1 =
		new RoundedShapeDrawable(binding.borderLayout)
		.radius(28)
		.backgroundFrom(
		com.google.android.material.R.attr
		.colorOutlineVariant,
		com.google.android.material.R.attr
		.colorSurfaceContainer,
		0.85f
		)
		.applyToView();
		
		RoundedShapeDrawable borderDrawable2 =
		new RoundedShapeDrawable(binding.borderLayout2)
		.radius(28)
		.backgroundFrom(
		com.google.android.material.R.attr
		.colorOutlineVariant,
		com.google.android.material.R.attr
		.colorSurfaceContainer,
		0.85f
		)
		.applyToView();
		
		materialDistributionBar.attachBodyOutline(bodyOutlineDrawable);
		materialDistributionBar.attachBorderDrawable(borderDrawable1);
		materialDistributionBar.attachBorderDrawable(borderDrawable2);
		
		// 5. appCountLayout pill + imageview1 tint (first-frame seed color)
		int primaryContainer =
		MaterialColors.getColor(
		binding.appCountLayout,
		com.google.android.material.R.attr.colorPrimaryContainer
		);
		
		int surfaceContainer =
		MaterialColors.getColor(
		binding.appCountLayout,
		com.google.android.material.R.attr.colorSurfaceContainer
		);
		
		int pillBlend =
		ColorUtils.blendARGB(
		primaryContainer,
		surfaceContainer,
		0.55f
		);
		
		int bodyBackground =
		MaterialColors.getColor(
		binding.appCountLayout,
		com.google.android.material.R.attr.colorSurfaceContainerLow
		);
		
		boolean isLight =
		MaterialColors.getColor(
		binding.appCountLayout,
		com.google.android.material.R.attr.colorSurface
		) > 0xFF7F7F7F;
		
		float amount =
		isLight ? 0.25f : 0.06f;
		
		int pillColor =
		ColorUtils.blendARGB(
		bodyBackground,
		pillBlend,
		1.0f - amount
		);
		
		RoundedShapeDrawable appCountPill =
		new RoundedShapeDrawable(binding.appCountLayout)
		.full()
		.backgroundColor(pillColor)
		.applyToView();
		
		materialDistributionBar.attachAppCountPill(
		appCountPill,
		binding.imageview1
		);
		
		// 6. labelLayout chip + imageview2 + labelText (first-frame seed colors)
		int labelFill =
		MaterialColors.getColor(
		binding.labelLayout,
		com.google.android.material.R.attr.colorSecondaryContainer
		);
		
		int labelOutline =
		MaterialColors.getColor(
		binding.labelLayout,
		com.google.android.material.R.attr.colorOutline
		);
		
		int labelStroke =
		ColorUtils.blendARGB(
		labelFill,
		labelOutline,
		0.35f
		);
		
		RoundedShapeDrawable labelChip =
		new RoundedShapeDrawable(binding.labelLayout)
		.radius(12)
		.backgroundColor(labelFill)
		.strokeColor(1, labelStroke)
		.applyToView();
		
		materialDistributionBar.attachLabelLayout(
		labelChip,
		binding.imageview2,
		binding.labelText
		);
		
		// 7. Emo Droid Lottie mascot
		materialDistributionBar.attachEmoDroid(
		binding.emoDroid
		);
		
		// 7b. Cookie ripple rings behind appCountTextLayout.
		//     Index 0 puts the rings behind the cookie; their color follows
		//     the indicator color owned by MaterialDistributionBar.
		CookieRippleView cookieRipple = new CookieRippleView(this);
		binding.raderEffectLayout.addView(
		cookieRipple,
		0,
		new android.widget.FrameLayout.LayoutParams(
		android.view.ViewGroup.LayoutParams.MATCH_PARENT,
		android.view.ViewGroup.LayoutParams.MATCH_PARENT
		)
		);
		materialDistributionBar.attachRippleView(cookieRipple);
		
		// 8. InstallerTabController (built LAST so every attach*() is registered)
		installerTabController =
		new InstallerTabController(
		this,
		binding.installerTabs,
		appCountAnimator,
		materialDistributionBar
		);
		_setFont();
		_theHelper();
		_materialTheme();
		updateHandle = UpdateManager.scheduleAutoCheck(this);
		circlesBg = FloatingCirclesDrawable.attachTo(binding.rootLayout);
		glassBg = GlassBackdropDrawable.attachTo(
		binding.bodyOutline, binding.rootLayout, 28f);
		materialDistributionBar.setBodyFillAlpha(70);
		
		glassLabel = GlassBackdropDrawable.attachTo(
		binding.labelLayout, binding.rootLayout, /* radius */ 14f);
		materialDistributionBar.setLabelFillAlpha(/* alpha */ 70);
		
		glassAppCount = GlassBackdropDrawable.attachTo(
		binding.appCountLayout, binding.rootLayout, /* radius */ 21f);
		materialDistributionBar.setAppCountFillAlpha(/* alpha */ 50);
		ReleaseNotesDialog.showForCurrentVersion(this);
		
		//ReleaseNotesDialog.show(this);
	}
	
	
	@Override
	public void onResume() {
		super.onResume();
		activityResumed = true;
		
		if (installedAppTracker == null ||
		appCountAnimator == null ||
		installerTabController == null) {
			return;
		}
		
		installedAppTracker.checkForChanges(
		new InstalledAppTracker.Callback() {
			
			@Override
			public void onResult(
			InstalledAppTracker.Result result) {
				
				if (!activityResumed) {
					return;
				}
				
				if (result == null ||
				!result.isSuccess()) {
					return;
				}
				
				installerTabController.update(
				result.getCount(),
				result.getInstallerInfos(),
				result.isFirstRun()
				);
				
				if (result.isFirstRun()) {
					
					installedAppTracker
					.markFirstAnimationShown();
				}
			}
		}
		);
	}
	
	@Override
	public void onPause() {
		super.onPause();
		activityResumed = false;
		
		if (appCountAnimator != null) {
			appCountAnimator.finish();
		}
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		activityResumed = false;
		
		// 1. Animator
		try {
			if (appCountAnimator != null) {
				appCountAnimator.destroy();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "appCountAnimator destroy failed", t);
		} finally {
			appCountAnimator = null;
		}
		
		// 2. Tracker
		try {
			if (installedAppTracker != null) {
				installedAppTracker.shutdown();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "installedAppTracker shutdown failed", t);
		} finally {
			installedAppTracker = null;
		}
		
		// 3. Touch helpers
		try {
			DroidPressBounce.detach(binding.emoDroid);
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "DroidPressBounce detach failed", t);
		}
		
		try {
			MaterialSettingsRipple.detach(binding.settingsLayout);
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "MaterialSettingsRipple detach failed", t);
		}
		
		// 4. Haptic manager
		try {
			if (hapticManager != null) {
				hapticManager.destroy();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "hapticManager destroy failed", t);
		} finally {
			hapticManager = null;
		}
		
		// 5. Toolbar helper
		try {
			if (toolbarScrollHelper != null) {
				toolbarScrollHelper.detach();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "toolbarScrollHelper detach failed", t);
		} finally {
			toolbarScrollHelper = null;
		}
		
		// 6. Panel controller (its destroy() forwards to the bar)
		try {
			if (installerTabController != null) {
				installerTabController.destroy();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "installerTabController destroy failed", t);
		} finally {
			installerTabController = null;
		}
		
		// 7. Distribution bar
		try {
			if (materialDistributionBar != null) {
				materialDistributionBar.destroy();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "materialDistributionBar destroy failed", t);
		} finally {
			materialDistributionBar = null;
		}
		
		// 8. Update check
		try {
			if (updateHandle != null) {
				updateHandle.cancel();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "updateHandle cancel failed", t);
		} finally {
			updateHandle = null;
		}
		
		// 9. Animated circles background
		try {
			if (circlesBg != null) {
				circlesBg.release();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "circlesBg release failed", t);
		} finally {
			circlesBg = null;
		}
		
		// 10. Glass backdrop layers — must be released AFTER the bar (step 7)
		//     so no further apply*FillColor() call can touch a released layer.
		try {
			if (glassBg != null) {
				glassBg.release();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "glassBg release failed", t);
		} finally {
			glassBg = null;
		}
		
		try {
			if (glassLabel != null) {
				glassLabel.release();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "glassLabel release failed", t);
		} finally {
			glassLabel = null;
		}
		
		try {
			if (glassAppCount != null) {
				glassAppCount.release();
			}
		} catch (Throwable t) {
			Log.e(DESTROY_TAG, "glassAppCount release failed", t);
		} finally {
			glassAppCount = null;
		}
	}
	
	@Override
	protected void attachBaseContext(Context newBase) {
		try {
			SettingsDialog.applySavedTheme(newBase);
		} catch (Throwable t) {
			android.util.Log.e("MainActivity", "attachBaseContext theme apply failed", t);
		}
		super.attachBaseContext(newBase);
	}
	public void _materialTheme() {
		/* ToolBar Color Switch  */ 
		
		toolbarScrollHelper =
		new MaterialToolbarScrollHelper(
		this,
		findViewById(R.id.toolBar),
		findViewById(R.id.installerTabs)
		);
		
		toolbarScrollHelper.attach();
		/* titleText Color */ 
		
		binding.titleText.setTextColor(
		MaterialColorsHelper.getColor(
		this,
		com.google.android.material.R.attr.colorOnSurface
		)
		);
	}
	
	
	public void _theHelper() {
		/* ToolBar Position */ 
		
		androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
		binding.toolBar,
		new androidx.core.view.OnApplyWindowInsetsListener() {
			@Override
			public androidx.core.view.WindowInsetsCompat onApplyWindowInsets(
			View v,
			androidx.core.view.WindowInsetsCompat insets) {
				
				androidx.core.graphics.Insets statusBars =
				insets.getInsets(
				androidx.core.view.WindowInsetsCompat.Type.statusBars()
				);
				
				v.setPadding(
				v.getPaddingLeft(),
				statusBars.top,
				v.getPaddingRight(),
				v.getPaddingBottom()
				);
				
				return insets;
			}
		}
		);
		
		androidx.core.view.ViewCompat.requestApplyInsets(binding.toolBar);
		/* Emo Droid Bounce */ 
		
		DroidPressBounce.attach(
		findViewById(R.id.emo_droid)
		);
		
		
		/* Haptic Feedback  */ 
		
		/* Haptic Feedback  */ 
		
		hapticManager =
		new HapticFeedbackManager(this);
		
		findViewById(R.id.emo_droid).setOnClickListener(
		new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				hapticManager.cycleNextLevel();
				hapticManager.performFeedback(v);
				hapticManager.showStatusSnackbar(
				findViewById(android.R.id.content)
				);
			}
		}
		);
		/* Settings Ripple */ 
		
		MaterialSettingsRipple.attach(
		findViewById(R.id.settingsLayout),
		findViewById(R.id.installerTabs)
		);
		/* Settings OnClick */ 
		
		findViewById(R.id.settingsLayout).setOnClickListener(
		new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				SettingsDialog.show(MainActivity.this);
			}
		}
		);
		/* Update Dialog */ 
		
		
	}
	
	
	public void _setFont() {
		/* Fonts */ 
		
		SystemFont.bold(binding.titleText);
		SystemFont.black(binding.appCountText);
		SystemFont.regular(binding.labelText);
	}
	
}