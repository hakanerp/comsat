/*
 * COMSAT
 * Copyright (c) 2015-2016, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.comsat.webactors.netty;

import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.ActorImpl;
import co.paralleluniverse.actors.ActorRef;
import co.paralleluniverse.actors.ActorSpec;
import co.paralleluniverse.common.reflection.AnnotationUtil;
import co.paralleluniverse.common.reflection.ClassLoaderUtil;
import co.paralleluniverse.common.util.Pair;
import co.paralleluniverse.comsat.webactors.WebActor;
import co.paralleluniverse.comsat.webactors.WebMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * @author circlespainter
 */
public class AutoWebActorHandler extends WebActorHandler {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(AutoWebActorHandler.class);
    private static final List<Class<?>> actorClasses = new ArrayList<>(32);
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    public AutoWebActorHandler() {
        this(null, null, null, null);
    }

    public AutoWebActorHandler(List<String> packagePrefixes) {
        this(null, null, packagePrefixes, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, List<String> packagePrefixes) {
        this(httpResponseEncoderName, null, packagePrefixes, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName) {
        this(httpResponseEncoderName, null, null, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, ClassLoader userClassLoader) {
        this(httpResponseEncoderName, userClassLoader, null, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, ClassLoader userClassLoader, List<String> packagePrefixes) {
        this(httpResponseEncoderName, userClassLoader, packagePrefixes, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, Map<Class<?>, Object[]> actorParams) {
        this(httpResponseEncoderName, null, null, actorParams);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, List<String> packagePrefixes, Map<Class<?>, Object[]> actorParams) {
        this(httpResponseEncoderName, null, packagePrefixes, actorParams);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, ClassLoader userClassLoader, List<String> packagePrefixes, Map<Class<?>, Object[]> actorParams) {
        super(null, httpResponseEncoderName);
        super.contextProvider = newContextProvider(userClassLoader != null ? userClassLoader : ClassLoader.getSystemClassLoader(), packagePrefixes, actorParams);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, AutoContextProvider prov) {
        super(prov, httpResponseEncoderName);
    }

    protected AutoContextProvider newContextProvider(ClassLoader userClassLoader, List<String> packagePrefixes, Map<Class<?>, Object[]> actorParams) {
        return new AutoContextProvider(userClassLoader, packagePrefixes, actorParams);
    }

    public static class AutoContextProvider implements WebActorContextProvider {
        private final ClassLoader userClassLoader;
        private final List<String> packagePrefixes;
        private final Map<Class<?>, Object[]> actorParams;
        private final Long defaultContextValidityMS;

        public AutoContextProvider(ClassLoader userClassLoader, List<String> packagePrefixes, Map<Class<?>, Object[]> actorParams) {
            this(userClassLoader, packagePrefixes, actorParams, null);
        }

        public AutoContextProvider(ClassLoader userClassLoader, List<String> packagePrefixes, Map<Class<?>, Object[]> actorParams, Long defaultContextValidityMS) {
            this.userClassLoader = userClassLoader;
            this.packagePrefixes = packagePrefixes;
            this.actorParams = actorParams;
            this.defaultContextValidityMS = defaultContextValidityMS;
        }

        @Override
        public final Context get(final FullHttpRequest req) {
            final String sessionId = getSessionId(req);
            if (sessionId != null && sessionsEnabled()) {
                final Context actorContext = sessions.get(sessionId);
                if (actorContext != null) {
                    if (actorContext.renew())
                        return actorContext;
                    else
                        sessions.remove(sessionId); // Evict session
                }
            }
            return newActorContext(req);
        }

        protected AutoContext newActorContext(FullHttpRequest req) {
            final AutoContext c = new AutoContext(req, packagePrefixes, actorParams, userClassLoader);
            if (defaultContextValidityMS !=  null)
                c.setValidityMS(defaultContextValidityMS);
            return c;
        }

        private String getSessionId(FullHttpRequest req) {
            final Set<Cookie> cookies = NettyHttpRequest.getNettyCookies(req);
            if (cookies != null) {
                for (final Cookie c : cookies) {
                    if (c != null && SESSION_COOKIE_KEY.equals(c.name()))
                        return c.value();
                }
            }
            return null;
        }
    }

    private static class AutoContext extends DefaultContextImpl {
        private String id;

        private final List<String> packagePrefixes;
        private final Map<Class<?>, Object[]> actorParams;
        private final ClassLoader userClassLoader;
        private Class<? extends ActorImpl<? extends WebMessage>> actorClass;
        private ActorRef<? extends WebMessage> actorRef;

        public AutoContext(FullHttpRequest req, List<String> packagePrefixes, Map<Class<?>, Object[]> actorParams, ClassLoader userClassLoader) {
            this.packagePrefixes = packagePrefixes;
            this.actorParams = actorParams;
            this.userClassLoader = userClassLoader;
            fillActor(req);
        }

        private void fillActor(FullHttpRequest req) {
            final Pair<ActorRef<? extends WebMessage>, Class<? extends ActorImpl<? extends WebMessage>>> p = autoCreateActor(req);
            if (p != null) {
                actorRef = p.getFirst();
                actorClass = p.getSecond();
            }
        }

        @Override
        public final String getId() {
            return id != null ? id : (id = UUID.randomUUID().toString());
        }

        @Override
        public final void restart(FullHttpRequest req) {
            renewed = new Date().getTime();
            fillActor(req);
        }

        @Override
        public final ActorRef<? extends WebMessage> getWebActor() {
            return actorRef;
        }

        @Override
        public final boolean handlesWithHttp(String uri) {
            return WebActorHandler.handlesWithHttp(uri, actorClass);
        }

        @Override
        public final boolean handlesWithWebSocket(String uri) {
            return WebActorHandler.handlesWithWebSocket(uri, actorClass);
        }

        @Override
        public WatchPolicy watch() {
            return WatchPolicy.DIE_IF_EXCEPTION_ELSE_RESTART;
        }

        @SuppressWarnings("unchecked")
        private Pair<ActorRef<? extends WebMessage>, Class<? extends ActorImpl<? extends WebMessage>>> autoCreateActor(FullHttpRequest req) {
            registerActorClasses();
            final String uri = req.getUri();
            for (final Class<?> c : actorClasses) {
                if (WebActorHandler.handlesWithHttp(uri, c) || WebActorHandler.handlesWithWebSocket(uri, c))
                    return new Pair<ActorRef<? extends WebMessage>, Class<? extends ActorImpl<? extends WebMessage>>>(
                        Actor.newActor (
                            new ActorSpec(c, actorParams != null ? actorParams.get(c) : EMPTY_OBJECT_ARRAY)
                        ).spawn(),
                        (Class<? extends ActorImpl<? extends WebMessage>>) c
                    );
            }
            return null;
        }

        private synchronized void registerActorClasses() {
            if (actorClasses.isEmpty()) {
                try {
                    final ClassLoader classLoader = userClassLoader != null ? userClassLoader : this.getClass().getClassLoader();
                    ClassLoaderUtil.accept((URLClassLoader) classLoader, new ClassLoaderUtil.Visitor() {
                        @Override
                        public final void visit(String resource, URL url, ClassLoader cl) {
                            if (packagePrefixes != null) {
                                boolean found = false;
                                for (final String packagePrefix : packagePrefixes) {
                                    if (packagePrefix != null && resource.startsWith(packagePrefix.replace('.', '/'))) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found)
                                    return;
                            }
                            if (!ClassLoaderUtil.isClassFile(resource))
                                return;
                            final String className = ClassLoaderUtil.resourceToClass(resource);
                            try (final InputStream is = cl.getResourceAsStream(resource)) {
                                if (AnnotationUtil.hasClassAnnotation(WebActor.class, is))
                                    registerWebActor(cl.loadClass(className));
                            } catch (final IOException | ClassNotFoundException e) {
                                log.error("Exception while scanning class " + className + " for WebActor annotation", e);
                                throw new RuntimeException(e);
                            }
                        }

                        private void registerWebActor(Class<?> c) {
                            actorClasses.add(c);
                        }
                    });
                } catch (final IOException e) {
                    log.error("IOException while scanning classes for WebActor annotation", e);
                }
            }
        }
    }
}
