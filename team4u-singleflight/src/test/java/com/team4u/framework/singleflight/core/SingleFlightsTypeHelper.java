package com.team4u.framework.singleflight.core;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.singleflight.api.SingleFlightExecution;

import java.util.List;

public final class SingleFlightsTypeHelper {

    private SingleFlightsTypeHelper() {
    }

    public static <T> T execute(SingleFlightEngine engine, String point,
                                java.util.Map<String, Object> arguments,
                                TypeReference<T> type,
                                SingleFlightExecution.SingleFlightLoader<T> loader) {
        return engine.execute(SingleFlightExecution.of(point, arguments, type, loader));
    }

    public static class UserList extends TypeReference<List<SingleFlightEngineTest.User>> {
    }
}
