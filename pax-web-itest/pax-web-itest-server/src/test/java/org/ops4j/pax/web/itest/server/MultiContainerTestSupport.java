/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.itest.server;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collection;
import java.util.Hashtable;
import java.util.function.Supplier;
import javax.servlet.Filter;
import javax.servlet.Servlet;

import org.junit.After;
import org.junit.Before;
import org.junit.runners.Parameterized;
import org.mockito.stubbing.Answer;
import org.ops4j.pax.web.extender.whiteboard.internal.ExtenderContext;
import org.ops4j.pax.web.extender.whiteboard.internal.tracker.FilterTracker;
import org.ops4j.pax.web.extender.whiteboard.internal.tracker.ServletTracker;
import org.ops4j.pax.web.itest.server.support.Utils;
import org.ops4j.pax.web.service.WebContainer;
import org.ops4j.pax.web.service.internal.HttpServiceEnabled;
import org.ops4j.pax.web.service.spi.ServerController;
import org.ops4j.pax.web.service.spi.config.Configuration;
import org.ops4j.pax.web.service.spi.model.OsgiContextModel;
import org.ops4j.pax.web.service.spi.model.ServerModel;
import org.ops4j.pax.web.service.spi.model.elements.FilterModel;
import org.ops4j.pax.web.service.spi.model.elements.ServletModel;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MultiContainerTestSupport {

	public static Logger LOG = LoggerFactory.getLogger(ServerControllerScopesTest.class);

	protected int port;

	protected ServerController controller;
	protected Configuration config;
	protected ServerModel serverModel;

	protected Bundle whiteboardBundle;
	protected BundleContext whiteboardBundleContext;

	protected HttpServiceEnabled container;
	protected ServiceReference<WebContainer> containerRef;

	protected ExtenderContext whiteboard;

	@Parameterized.Parameter
	public Runtime runtime;

	private ServiceTrackerCustomizer<Servlet, ServletModel> servletCustomizer;
	private ServiceTrackerCustomizer<Filter, FilterModel> filterCustomizer;

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> data() {
		return Arrays.asList(new Object[][] {
				{ Runtime.JETTY },
				{ Runtime.TOMCAT },
				{ Runtime.UNDERTOW },
		});
	}

	public void configurePort() throws Exception {
		ServerSocket serverSocket = new ServerSocket(0);
		port = serverSocket.getLocalPort();
		serverSocket.close();
	}

	@Before
	@SuppressWarnings("unchecked")
	public void initAll() throws Exception {
		configurePort();

		controller = Utils.create(null, port, runtime, getClass().getClassLoader());
		controller.configure();
		controller.start();

		config = controller.getConfiguration();

		serverModel = new ServerModel(new Utils.SameThreadExecutor());
		serverModel.createDefaultServletContextModel(controller);

		whiteboardBundle = mockBundle("org.ops4j.pax.web.pax-web-extender-whiteboard");
		whiteboardBundleContext = whiteboardBundle.getBundleContext();

		OsgiContextModel.DEFAULT_CONTEXT_MODEL.setOwnerBundle(whiteboardBundle);

		when(whiteboardBundleContext.createFilter(anyString()))
				.thenAnswer(invocation -> FrameworkUtil.createFilter(invocation.getArgument(0, String.class)));

		container = new HttpServiceEnabled(whiteboardBundle, controller, serverModel, null, config);

		containerRef = mock(ServiceReference.class);
		when(whiteboardBundleContext.getService(containerRef)).thenReturn(container);

		whiteboard = new ExtenderContext(null, whiteboardBundleContext);
		whiteboard.webContainerAdded(containerRef);

		servletCustomizer = getCustomizer(ServletTracker.createTracker(whiteboard, whiteboardBundleContext));
		filterCustomizer = getCustomizer(FilterTracker.createTracker(whiteboard, whiteboardBundleContext));
	}

	@After
	public void cleanup() throws Exception {
		if (container != null) {
			controller.stop();
			whiteboard.webContainerRemoved(containerRef);
		}
	}

	/**
	 * Helper method to create mock {@link Bundle} with associated mock {@link BundleContext}.
	 * @param symbolicName
	 * @return
	 */
	protected Bundle mockBundle(String symbolicName) {
		Bundle bundle = mock(Bundle.class);
		BundleContext bundleContext = mock(BundleContext.class);
		when(bundle.getSymbolicName()).thenReturn("sample1");
		when(bundle.getBundleContext()).thenReturn(bundleContext);
		when(bundleContext.getBundle()).thenReturn(bundle);

		return bundle;
	}

	/**
	 * Creates mock {@link ServiceReference} to represent OSGi-registered {@link Servlet} instance
	 * @param bundle
	 * @param name
	 * @param supplier
	 * @param serviceId
	 * @param rank
	 * @param patterns
	 * @return
	 */
	protected ServiceReference<Servlet> mockServletReference(Bundle bundle, String name,
			Supplier<Servlet> supplier, Long serviceId, Integer rank, String... patterns) {
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, name);
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, patterns);

		Servlet instance = supplier.get();
		try {
			when(bundle.loadClass(instance.getClass().getName()))
					.thenAnswer((Answer<Class<?>>) invocation -> instance.getClass());
			when(bundle.loadClass(Servlet.class.getName()))
					.thenAnswer((Answer<Class<?>>) invocation -> Servlet.class);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e.getMessage(), e);
		}

		ServiceReference<Servlet> servletRef = mockReference(bundle, Servlet.class, props, serviceId, rank);
		when(bundle.getBundleContext().getService(servletRef)).thenReturn(instance);
		when(whiteboardBundleContext.getService(servletRef)).thenReturn(instance);

		return servletRef;
	}

	/**
	 * Creates mock {@link ServiceReference} to represent OSGi-registered {@link Filter} instance
	 * @param bundle
	 * @param name
	 * @param supplier
	 * @param serviceId
	 * @param rank
	 * @param patterns
	 * @return
	 */
	protected ServiceReference<Filter> mockFilterReference(Bundle bundle, String name,
			Supplier<Filter> supplier, Long serviceId, Integer rank, String... patterns) {
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, name);
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, patterns);

		Filter instance = supplier.get();
		try {
			when(bundle.loadClass(instance.getClass().getName()))
					.thenAnswer((Answer<Class<?>>) invocation -> instance.getClass());
			when(bundle.loadClass(Filter.class.getName()))
					.thenAnswer((Answer<Class<?>>) invocation -> Filter.class);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e.getMessage(), e);
		}

		ServiceReference<Filter> filterRef = mockReference(bundle, Filter.class, props, serviceId, rank);
		when(bundle.getBundleContext().getService(filterRef)).thenReturn(instance);
		when(whiteboardBundleContext.getService(filterRef)).thenReturn(instance);

		return filterRef;
	}

	protected <S> ServiceReference<S> mockReference(Bundle bundle, Class<S> clazz, Hashtable<String, Object> props) {
		return mockReference(bundle, clazz, props, 0L, 0);
	}

	@SuppressWarnings("unchecked")
	protected <S> ServiceReference<S> mockReference(Bundle bundle, Class<S> clazz, Hashtable<String, Object> props,
			Long serviceId, Integer rank) {
		ServiceReference<S> ref = mock(ServiceReference.class);

		when(ref.getProperty(Constants.OBJECTCLASS)).thenReturn(new String[] { clazz.getName() });

		when(ref.getProperty(Constants.SERVICE_ID)).thenReturn(serviceId);
		when(ref.getProperty(Constants.SERVICE_RANKING)).thenReturn(rank);

		when(ref.getBundle()).thenReturn(bundle);

		props.forEach((k, v) -> when(ref.getProperty(k)).thenReturn(v));
		when(ref.getPropertyKeys()).thenReturn(props.keySet().toArray(new String[0]));

		return ref;
	}

	public ServiceTrackerCustomizer<Servlet, ServletModel> getServletCustomizer() {
		return servletCustomizer;
	}

	public ServiceTrackerCustomizer<Filter, FilterModel> getFilterCustomizer() {
		return filterCustomizer;
	}

	@SuppressWarnings("unchecked")
	protected <S, T> ServiceTrackerCustomizer<S, T> getCustomizer(ServiceTracker<S, T> tracker) {
		tracker.open();
		try {
			Field f = ServiceTracker.class.getDeclaredField("customizer");
			f.setAccessible(true);
			return (ServiceTrackerCustomizer<S, T>) f.get(tracker);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

}
