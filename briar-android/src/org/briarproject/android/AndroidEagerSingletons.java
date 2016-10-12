package org.briarproject.android;

import org.briarproject.system.AndroidSystemModule;

class AndroidEagerSingletons {

	static void initEagerSingletons(AndroidComponent c) {
		c.inject(new AppModule.EagerSingletons());
		c.inject(new AndroidSystemModule.EagerSingletons());
	}
}
