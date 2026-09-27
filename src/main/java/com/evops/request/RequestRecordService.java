package com.evops.request;

import com.evops.common.BusinessException;
import com.evops.request.entity.RequestRecord;
import com.evops.request.mapper.RequestRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 请求号台账。新增/变更请求必须先在此登记：
 * 唯一约束保证同一请求号重放/并发提交只有一笔被接受；
 * 登记与业务写入处于同一事务，业务失败则台账一并回退。
 */
@Service
public class RequestRecordService {

    private final RequestRecordMapper requestRecordMapper;

    public RequestRecordService(RequestRecordMapper requestRecordMapper) {
        this.requestRecordMapper = requestRecordMapper;
    }

    public void register(String requestNo, String bizType, String bizKey) {
        RequestRecord record = new RequestRecord();
        record.setRequestNo(requestNo);
        record.setBizType(bizType);
        record.setBizKey(bizKey);
        try {
            requestRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("请求号 " + requestNo + " 已处理，请勿重复提交");
        }
    }
}
