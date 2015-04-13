/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play.db.jpa;

import play.db.DBApi;
import play.inject.ApplicationLifecycle;
import play.libs.F;

import java.util.*;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.persistence.*;

/**
 * Default implementation of the JPA API.
 */
public class DefaultJPAApi implements JPAApi {

    private final JPAConfig jpaConfig;

    private final Map<String, EntityManagerFactory> emfs = new HashMap<String, EntityManagerFactory>();

    public DefaultJPAApi(JPAConfig jpaConfig) {
        this.jpaConfig = jpaConfig;
    }

    @Singleton
    public static class JPAApiProvider implements Provider<JPAApi> {
        private final JPAApi jpaApi;

        @Inject
        public JPAApiProvider(JPAConfig jpaConfig, DBApi dbApi, ApplicationLifecycle lifecycle) {
            // dependency on db api ensures that the databases are initialised
            jpaApi = new DefaultJPAApi(jpaConfig);
            lifecycle.addStopHook(() -> {
                jpaApi.shutdown();
                return F.Promise.pure(null);
            });
            jpaApi.start();
        }

        @Override
        public JPAApi get() {
            return jpaApi;
        }
    }

    /**
     * Initialise JPA entity manager factories.
     */
    public JPAApi start() {
        for (JPAConfig.PersistenceUnit persistenceUnit : jpaConfig.persistenceUnits()) {
            emfs.put(persistenceUnit.name, Persistence.createEntityManagerFactory(persistenceUnit.unitName));
        }
        return this;
    }

    /**
     * Get the EntityManager for the specified persistence unit name.
     *
     * @param name The persistence unit name
     */
    public EntityManager em(String name) {
        EntityManagerFactory emf = emfs.get(name);
        if (emf == null) {
            return null;
        }
        return emf.createEntityManager();
    }

    /**
     * Run a block of code in a JPA transaction.
     *
     * @param block Block of code to execute
     */
    public <T> T withTransaction(play.libs.F.Function0<T> block) throws Throwable {
        return withTransaction("default", false, block);
    }

    /**
     * Run a block of asynchronous code in a JPA transaction.
     *
     * @param block Block of code to execute
     *
     * @deprecated This may cause deadlocks
     */
    @Deprecated
    public <T> F.Promise<T> withTransactionAsync(play.libs.F.Function0<F.Promise<T>> block) throws Throwable {
        return withTransactionAsync("default", false, block);
    }

    /**
     * Run a block of code in a JPA transaction.
     *
     * @param block Block of code to execute
     */
    public void withTransaction(final play.libs.F.Callback0 block) {
        try {
            withTransaction("default", false, () -> {
                block.invoke();
                return null;
            });
        } catch (Throwable t) {
            throw new RuntimeException("JPA transaction failed", t);
        }
    }

    /**
     * Run a block of code in a JPA transaction.
     *
     * @param name The persistence unit name
     * @param readOnly Is the transaction read-only?
     * @param block Block of code to execute
     */
    public <T> T withTransaction(String name, boolean readOnly, play.libs.F.Function0<T> block) throws Throwable {
        EntityManager em = null;
        EntityTransaction tx = null;

        try {
            em = em(name);

            if (em == null) {
                throw new RuntimeException("No JPA entity manager defined for '" + name + "'");
            }

            JPA.bindForSync(em);

            if (!readOnly) {
                tx = em.getTransaction();
                tx.begin();
            }

            T result = block.apply();

            if (tx != null) {
                if(tx.getRollbackOnly()) {
                    tx.rollback();
                } else {
                    tx.commit();
                }
            }

            return result;

        } catch (Throwable t) {
            if (tx != null) {
                try { tx.rollback(); } catch (Throwable e) {}
            }
            throw t;
        } finally {
            JPA.bindForSync(null);
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Run a block of asynchronous code in a JPA transaction.
     *
     * @param name The persistence unit name
     * @param readOnly Is the transaction read-only?
     * @param block Block of code to execute.
     *
     * @deprecated This may cause deadlocks
     */
    @Deprecated
    public <T> F.Promise<T> withTransactionAsync(String name, boolean readOnly, play.libs.F.Function0<F.Promise<T>> block) throws Throwable {
        EntityManager em = null;
        EntityTransaction tx = null;
        try {

            em = em(name);
            JPA.bindForAsync(em);

            if (!readOnly) {
                tx = em.getTransaction();
                tx.begin();
            }

            F.Promise<T> result = block.apply();

            final EntityManager fem = em;
            final EntityTransaction ftx = tx;

            F.Promise<T> committedResult = result.map(new F.Function<T, T>() {
                @Override
                public T apply(T t) throws Throwable {
                    if (ftx != null) {
                        if (ftx.getRollbackOnly()) {
                            ftx.rollback();
                        } else {
                            ftx.commit();
                        }
                    }
                    return t;
                }
            });

            committedResult.onFailure(t -> {
                if (ftx != null) {
                    try { if (ftx.isActive()) { ftx.rollback(); } } catch (Throwable e) {}
                }
                try {
                    fem.close();
                } finally {
                    JPA.bindForAsync(null);
                }
            });
            committedResult.onRedeem(t -> {
                try {
                    fem.close();
                } finally {
                    JPA.bindForAsync(null);
                }
            });

            return committedResult;

        } catch (Throwable t) {
            if (tx != null) {
                try { tx.rollback(); } catch (Throwable e) {}
            }
            if (em != null) {
                try {
                    em.close();
                } finally {
                    JPA.bindForAsync(null);
                }
            }
            throw t;
        }
    }

    /**
     * Close all entity manager factories.
     */
    public void shutdown() {
        for (EntityManagerFactory emf : emfs.values()) {
            emf.close();
        }
    }

}
