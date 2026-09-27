package com.evops.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 接口层：统一返回结构、必填校验、按单号检索闭环。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_ok() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.service").value("evops"));
    }

    @Test
    void createMachine_missingFields_returnsUnifiedFail() throws Exception {
        mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能为空")));
    }

    @Test
    void createThenQueryWorkOrder_closedLoop() throws Exception {
        String machineCode = "M-API-" + System.nanoTime();
        String machineBody = "{\"machineCode\":\"" + machineCode + "\",\"machineName\":\"接口测试机\","
                + "\"requestNo\":\"REQ-API-M-" + System.nanoTime() + "\"}";
        String machineResponse = mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andReturn().getResponse().getContentAsString();
        Long machineId = ((Number) com.jayway.jsonpath.JsonPath.read(machineResponse, "$.data.id")).longValue();

        String orderNo = "WO-API-" + System.nanoTime();
        String orderBody = "{\"orderNo\":\"" + orderNo + "\",\"machineId\":" + machineId
                + ",\"operationType\":\"播种\",\"requestNo\":\"REQ-API-W-" + System.nanoTime() + "\"}";
        mockMvc.perform(post("/api/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(get("/api/work-orders").param("orderNo", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.order.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.statusLogs[0].toStatus").value("CREATED"));
    }

    @Test
    void replaySameRequestNo_rejected() throws Exception {
        String requestNo = "REQ-API-DUP-" + System.nanoTime();
        String body1 = "{\"machineCode\":\"M-DUP1-" + System.nanoTime() + "\",\"machineName\":\"一号\","
                + "\"requestNo\":\"" + requestNo + "\"}";
        mockMvc.perform(post("/api/machines").contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(jsonPath("$.success").value(true));

        String body2 = "{\"machineCode\":\"M-DUP2-" + System.nanoTime() + "\",\"machineName\":\"二号\","
                + "\"requestNo\":\"" + requestNo + "\"}";
        mockMvc.perform(post("/api/machines").contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已处理")));
    }
}
