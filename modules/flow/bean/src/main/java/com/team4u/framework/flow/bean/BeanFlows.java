package com.team4u.framework.flow.bean;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;

/** Local flow compilation backed by beans from a {@link BeanManager}. */
public final class BeanFlows {
    private BeanFlows() { }

    /** Compiles using the global {@link BeanManager}. */
    public static <I, O> LocalExecutable<I, O> compile(Flow<I, O> flow) {
        return compile(flow, BeanManager.getInstance());
    }

    /** Compiles using the supplied {@link BeanManager}. */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, BeanManager beanManager) {
        return Local.compile(flow, new BeanOperationResolver(beanManager));
    }
}
