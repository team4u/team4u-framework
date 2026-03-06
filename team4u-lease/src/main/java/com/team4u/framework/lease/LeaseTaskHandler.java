package com.team4u.framework.lease;

/**
 * 租约任务处理器接口。
 * <p>
 * 实现类需定义具体的业务处理逻辑。
 */
public interface LeaseTaskHandler {

    /**
     * 获取处理器对应的任务类型 Key。
     *
     * @return 任务类型唯一标识
     */
    String key();

    /**
     * 处理任务逻辑。
     *
     * @param payload 任务业务负载数据
     * @throws Exception 处理过程中抛出的任何异常
     */
    void handle(String payload) throws Exception;
}
