/*
 * Copyright (c) 2006-2011 Daniel Yuan.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package org.operamasks.el.script;

import java.util.List;
import java.util.ArrayList;
import java.util.Hashtable;
import javax.el.ExpressionFactory;
import javax.el.ELContextListener;
import javax.el.ELContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngine;
import javax.script.ScriptContext;

import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.resolver.PackageCollector;
import org.operamasks.el.resolver.SystemClassELResolver;
import org.operamasks.el.resolver.ClassResolver;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleListener;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.packageadmin.ExportedPackage;
import org.osgi.util.tracker.ServiceTracker;

public class Activator
    implements BundleActivator,
               PackageCollector,
               BundleListener,
               ServiceFactory
{
    private BundleContext bc;
    private ServiceTracker tracker;
    private volatile List<String> packages;

    private static final String[] EMPTY_STRINGS = new String[0];

    public void start(BundleContext bc) throws Exception {
        this.bc = bc;

        // register ScriptEngineFactory service factory
        Hashtable<String,Object> props = new Hashtable<String,Object>();
        ScriptEngineFactory factory = new ELiteScriptEngineFactory(); // used to get meta data
        props.put(ScriptEngine.ENGINE, factory.getEngineName());
        props.put(ScriptEngine.ENGINE_VERSION, factory.getEngineVersion());
        props.put(ScriptEngine.LANGUAGE, factory.getLanguageName());
        props.put(ScriptEngine.LANGUAGE_VERSION, factory.getLanguageVersion());
        props.put(ScriptEngine.NAME, factory.getNames().toArray(EMPTY_STRINGS));
        props.put("javax.script.extension", factory.getExtensions().toArray(EMPTY_STRINGS));
        props.put("javax.script.mime_type", factory.getMimeTypes().toArray(EMPTY_STRINGS));
        bc.registerService(ScriptEngineFactory.class.getName(), this, props);

        // register ExpressionFactory service
        bc.registerService(ExpressionFactory.class.getName(), ELEngine.getExpressionFactory(), null);

        // track the ELContextListener registration
        tracker = new ELContextListenerServiceTracker(bc);
        tracker.open();

        // collect exported packages
        bc.addBundleListener(this);
        SystemClassELResolver.setPackageCollector(this);
    }

    public void stop(BundleContext bc) throws Exception {
        tracker.close();
        bc.removeBundleListener(this);
        SystemClassELResolver.setPackageCollector(null);
    }

    public List<String> findPackages() {
        if (packages == null)
            packages = findExportedPackages();
        return packages;
    }

    private List<String> findExportedPackages() {
        List<String> names = new ArrayList<String>();
        ServiceReference ref = bc.getServiceReference(PackageAdmin.class.getName());
        if (ref != null) {
            PackageAdmin pkgadmin = (PackageAdmin)bc.getService(ref);
            ExportedPackage[] pkgs = pkgadmin.getExportedPackages((Bundle)null);
            if (pkgs != null) {
                for (ExportedPackage pkg : pkgs) {
                    names.add(pkg.getName());
                }
            }
            bc.ungetService(ref);
        }
        return names;
    }

    public void bundleChanged(BundleEvent event) {
        packages = null;
    }

    public Object getService(final Bundle bundle, ServiceRegistration registration) {
        return new ELiteScriptEngineFactory() {
            protected void contextCreated(ELContext elctx, ScriptContext sctx) {
                elctx.putContext(ClassResolver.class, new BundleClassResolver(bundle));
                sctx.setAttribute("bundle", bundle, ScriptContext.ENGINE_SCOPE);
                sctx.setAttribute("bundleContext", bundle.getBundleContext(), ScriptContext.ENGINE_SCOPE);
            }
        };
    }

    public void ungetService(Bundle bundle, ServiceRegistration registration, Object service) {
        // do nothing
    }

    static class BundleClassResolver extends ClassResolver {
        private Bundle bundle;
        private ClassLoader myloader;

        BundleClassResolver(Bundle bundle) {
            this.bundle = bundle;
            this.myloader = Activator.class.getClassLoader();
        }

        @Override
        protected Class<?> resolveClass0(String name) {
            // make sure class is visible in the bundle
            Class<?> c = super.resolveClass0(name);
            if (c != null) {
                ClassLoader cl = c.getClassLoader();
                if (cl != null && cl != myloader) {
                    try {
                        bundle.loadClass(name);
                    } catch (ClassNotFoundException ex) {
                        c = null;
                    }
                }
            }
            return c;
        }
    }

    static class ELContextListenerServiceTracker extends ServiceTracker {
        public ELContextListenerServiceTracker(BundleContext context) {
            super(context, ELContextListener.class.getName(), null);
        }

        public Object addingService(ServiceReference reference) {
            Object service = super.addingService(reference);
            if (service != null)
                ELEngine.addELContextListener((ELContextListener)service);
            return service;
        }

        public void removedService(ServiceReference reference, Object service) {
            ELEngine.removeELContextListener((ELContextListener)service);
            super.removedService(reference, service);
        }
    }
}
