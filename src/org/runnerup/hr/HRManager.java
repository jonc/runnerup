package org.runnerup.hr;

import java.util.ArrayList;
import java.util.List;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

@TargetApi(Build.VERSION_CODES.FROYO)
public class HRManager {

	public static HRProvider getHRProvider(Context ctx, String src) {
		if (src.contentEquals(SamsungBLEHRProvider.NAME)) {
			if (!SamsungBLEHRProvider.checkLibrary())
				return null;
			return new SamsungBLEHRProvider(ctx);
		}
		if (src.contentEquals(AndroidBLEHRProvider.NAME)) {
			if (!AndroidBLEHRProvider.checkLibrary(ctx))
				return null;
			return new AndroidBLEHRProvider(ctx);
		}
		if (src.contentEquals(MockHRProvider.NAME)) {
			return new MockHRProvider(ctx);
		}
		return null;
	}

	public static List<HRProvider> getHRProviderList(Context ctx) {
		List<HRProvider> providers = new ArrayList<HRProvider>();
		if (SamsungBLEHRProvider.checkLibrary()) {
			System.err.println("Samsung OK");
			providers.add(new SamsungBLEHRProvider(ctx));
		}
		
		if (AndroidBLEHRProvider.checkLibrary(ctx)) {
			System.err.println("AndroidBLE OK");
			providers.add(new AndroidBLEHRProvider(ctx));
		}
		
		if (providers.isEmpty()) {
			providers.add(new MockHRProvider(ctx));
		}
		
		return providers;
	}
}
