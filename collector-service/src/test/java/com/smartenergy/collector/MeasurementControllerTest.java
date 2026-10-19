package com.smartenergy.collector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MeasurementController.class)
@Import({ApiExceptionHandler.class, io.micrometer.core.instrument.simple.SimpleMeterRegistry.class})
class MeasurementControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    MeasurementPublisher publisher;

    private static final String VALID = """
            {"sensorId":"SENSOR-001","timestamp":"2026-09-30T08:15:00Z",
             "powerKw":3.72,"voltage":226.4,"temperature":29.1}""";

    @Test
    void validMeasurementIsAcceptedAndPublished() throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted());
        verify(publisher).publish(any());
    }

    @Test
    void missingSensorIdGives400AndNothingIsPublished() throws Exception {
        String body = VALID.replace("\"sensorId\":\"SENSOR-001\",", "");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("sensorId: must not be blank"));
        verify(publisher, never()).publish(any());
    }

    @Test
    void negativePowerIsRejected() throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("3.72", "-1")))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publish(any());
    }

    @Test
    void malformedTimestampIsRejected() throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("2026-09-30T08:15:00Z", "hier")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redisFailureGives503() throws Exception {
        when(publisher.publish(any())).thenThrow(new QueryTimeoutException("redis down"));
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable());
    }
}
