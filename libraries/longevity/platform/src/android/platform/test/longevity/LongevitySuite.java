/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.platform.test.longevity;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.platform.test.composer.Iterate;
import android.platform.test.composer.Shuffle;
import android.platform.test.longevity.listener.BatteryTerminator;
import android.platform.test.longevity.listener.ErrorTerminator;
import android.platform.test.longevity.listener.TimeoutTerminator;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import androidx.test.InstrumentationRegistry;

import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

/**
 * {@inheritDoc}
 *
 * This class is used for constructing longevity suites that run on an Android device.
 */
public class LongevitySuite extends android.host.test.longevity.LongevitySuite {
    private static final String LOG_TAG = LongevitySuite.class.getSimpleName();

    private Instrumentation mInstrumentation;
    private Context mContext;

    // Cached {@link TimeoutTerminator} instance.
    private TimeoutTerminator mTimeoutTerminator;

    /**
     * Takes a {@link Bundle} and maps all String K/V pairs into a {@link Map<String, String>}.
     *
     * @param bundle the input arguments to return in a {@link Map}
     * @return Map<String, String> all String-to-String key, value pairs in the {@link Bundle}
     */
    protected static final Map<String, String> toMap(Bundle bundle) {
        Map<String, String> result = new HashMap<>();
        for (String key : bundle.keySet()) {
            if (!bundle.containsKey(key)) {
                Log.w(LOG_TAG, String.format("Couldn't find value for option: %s", key));
            } else {
                // Arguments are assumed String <-> String
                result.put(key, bundle.getString(key));
            }
        }
        return result;
    }

    /**
     * Called reflectively on classes annotated with {@code @RunWith(LongevitySuite.class)}
     */
    public LongevitySuite(Class<?> klass, RunnerBuilder builder)
            throws InitializationError {
        this(klass, builder, InstrumentationRegistry.getInstrumentation(),
                InstrumentationRegistry.getContext(), InstrumentationRegistry.getArguments());
    }

    /**
     * Enables subclasses, e.g.{@link ProfileSuite}, to constuct a suite using its own list of
     * Runners.
     */
    protected LongevitySuite(Class<?> klass, List<Runner> runners, Bundle args)
            throws InitializationError {
        super(klass, runners, toMap(args));
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = InstrumentationRegistry.getContext();
    }

    /**
     * Used to pass in mock-able Android features for testing.
     */
    @VisibleForTesting
    public LongevitySuite(Class<?> klass, RunnerBuilder builder,
            Instrumentation instrumentation, Context context, Bundle arguments)
            throws InitializationError {
        this(klass, constructClassRunners(klass, builder, arguments), arguments);
        // Overwrite instrumentation and context here with the passed-in objects.
        mInstrumentation = instrumentation;
        mContext = context;
    }

    /**
     * Constructs the sequence of {@link Runner}s using platform composers.
     */
    private static List<Runner> constructClassRunners(
                Class<?> suite, RunnerBuilder builder, Bundle args)
            throws InitializationError {
        // TODO(b/118340229): Refactor to share logic with base class. In the meanwhile, keep the
        // logic here in sync with the base class.
        // Retrieve annotated suite classes.
        SuiteClasses annotation = suite.getAnnotation(SuiteClasses.class);
        if (annotation == null) {
            throw new InitializationError(String.format(
                    "Longevity suite, '%s', must have a SuiteClasses annotation", suite.getName()));
        }
        // Construct and store custom runners for the full suite.
        BiFunction<Bundle, List<Runner>, List<Runner>> modifier =
                new Iterate<Runner>().andThen(new Shuffle<Runner>());
        return modifier.apply(args, builder.runners(suite, annotation.value()));
    }

    @Override
    public void run(final RunNotifier notifier) {
        // Register the battery terminator available only on the platform library, if present.
        if (hasBattery()) {
            notifier.addListener(new BatteryTerminator(notifier, mArguments, mContext));
        }
        // Register other listeners and continue with standard longevity run.
        super.run(notifier);
    }

    /**
     * Returns the platform-specific {@link TimeoutTerminator} for Android devices.
     */
    @Override
    public android.host.test.longevity.listener.ErrorTerminator getErrorTerminator(
            final RunNotifier notifier) {
        return new ErrorTerminator(notifier);
    }

    /**
     * Returns the platform-specific {@link TimeoutTerminator} for Android devices.
     *
     * <p>This method will always return the same {@link TimeoutTerminator} instance.
     */
    @Override
    public android.host.test.longevity.listener.TimeoutTerminator getTimeoutTerminator(
            final RunNotifier notifier) {
        if (mTimeoutTerminator == null) {
            mTimeoutTerminator = new TimeoutTerminator(notifier, mArguments);
        }
        return mTimeoutTerminator;
    }

    /**
     * Determines if the device has a battery attached.
     */
    private boolean hasBattery () {
        final Intent batteryInfo =
                mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return batteryInfo.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
    }
}
