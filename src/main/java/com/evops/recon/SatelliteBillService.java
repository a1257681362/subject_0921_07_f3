package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.recon.dto.SatelliteBillReverseRequest;
import com.evops.recon.dto.SatelliteBillUploadRequest;
import com.evops.recon.entity.SatelliteBill;
import com.evops.recon.mapper.SatelliteBillMapper;
import com.evops.request.RequestRecordService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 卫星轨迹核算平台账单（对方账）。只录入/红冲，不做物理删除；
 * 红冲口径与本地作业单一致：原单置 REVERSED，另开金额取反的反向单。
 */
@Service
public class SatelliteBillService {

    private final SatelliteBillMapper billMapper;
    private final RequestRecordService requestRecordService;

    public SatelliteBillService(SatelliteBillMapper billMapper,
                                RequestRecordService requestRecordService) {
        this.billMapper = billMapper;
        this.requestRecordService = requestRecordService;
    }

    @Transactional(rollbackFor = Exception.class)
    public SatelliteBill upload(SatelliteBillUploadRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SATELLITE_BILL_UPLOAD, req.getBillNo());

        SatelliteBill bill = new SatelliteBill();
        bill.setBillNo(req.getBillNo());
        bill.setOrderNo(req.getOrderNo());
        bill.setMachineId(req.getMachineId());
        bill.setTrackArea(req.getTrackArea());
        bill.setBillTime(req.getBillTime());
        bill.setStatus("ACTIVE");
        bill.setReversalFlag("0");
        bill.setRemark(req.getRemark());
        try {
            billMapper.insert(bill);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("卫星账单号 " + req.getBillNo() + " 已存在");
        }
        return bill;
    }

    /** 红冲：原账单置 REVERSED，另开反向账单（账单号-R、面积取反）。 */
    @Transactional(rollbackFor = Exception.class)
    public SatelliteBill reverse(Long id, SatelliteBillReverseRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SATELLITE_BILL_UPLOAD, String.valueOf(id));

        SatelliteBill origin = billMapper.selectById(id);
        if (origin == null) {
            throw new BusinessException("卫星账单不存在: " + id);
        }
        if ("1".equals(origin.getReversalFlag())) {
            throw new BusinessException("反向账单不能再次红冲");
        }
        if ("REVERSED".equals(origin.getStatus())) {
            throw new BusinessException("卫星账单已红冲，不能重复红冲");
        }

        origin.setStatus("REVERSED");
        billMapper.updateById(origin);

        SatelliteBill reverse = new SatelliteBill();
        reverse.setBillNo(origin.getBillNo() + "-R");
        // 红冲账单的作业单号同样加 -R，与本地红冲作业单口径一致，保证两侧逐笔配对键相同。
        reverse.setOrderNo(origin.getOrderNo() + "-R");
        reverse.setMachineId(origin.getMachineId());
        reverse.setTrackArea(origin.getTrackArea().negate());
        reverse.setBillTime(origin.getBillTime());
        reverse.setStatus("REVERSED");
        reverse.setReversalFlag("1");
        reverse.setReversesBillId(origin.getId());
        reverse.setRemark(req.getRemark());
        try {
            billMapper.insert(reverse);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("账单 " + origin.getBillNo() + " 的红冲单已存在");
        }
        return reverse;
    }

    public SatelliteBill findByBillNo(String billNo) {
        SatelliteBill bill = billMapper.selectOne(
                new LambdaQueryWrapper<SatelliteBill>().eq(SatelliteBill::getBillNo, billNo));
        if (bill == null) {
            throw new BusinessException("卫星账单不存在: " + billNo);
        }
        return bill;
    }

    public List<SatelliteBill> listByOrderNo(String orderNo) {
        return billMapper.selectList(new LambdaQueryWrapper<SatelliteBill>()
                .eq(SatelliteBill::getOrderNo, orderNo)
                .orderByAsc(SatelliteBill::getId));
    }
}
