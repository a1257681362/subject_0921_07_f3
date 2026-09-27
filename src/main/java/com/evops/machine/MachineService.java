package com.evops.machine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.dto.MachineDisableRequest;
import com.evops.machine.dto.MachineUpdateRequest;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.RequestRecordService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MachineService {

    private final MachineMapper machineMapper;
    private final RequestRecordService requestRecordService;

    public MachineService(MachineMapper machineMapper, RequestRecordService requestRecordService) {
        this.machineMapper = machineMapper;
        this.requestRecordService = requestRecordService;
    }

    /** 农机建档。请求号登记与落库同事务，编号唯一约束兜底。 */
    @Transactional(rollbackFor = Exception.class)
    public Machine create(MachineCreateRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_CREATE, req.getMachineCode());

        Machine machine = new Machine();
        machine.setMachineCode(req.getMachineCode());
        machine.setMachineName(req.getMachineName());
        machine.setMachineType(req.getMachineType());
        machine.setPlateNo(req.getPlateNo());
        machine.setRemark(req.getRemark());
        machine.setStatus(MachineStatus.ACTIVE);
        try {
            machineMapper.insert(machine);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("农机编号 " + req.getMachineCode() + " 已存在");
        }
        return machine;
    }

    /** 变更农机信息，凭版本号乐观锁并发控制。 */
    @Transactional(rollbackFor = Exception.class)
    public Machine update(Long id, MachineUpdateRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_UPDATE, String.valueOf(id));

        Machine machine = requireMachine(id);
        if (MachineStatus.DISABLED.equals(machine.getStatus())) {
            throw new BusinessException("农机已停用，不能变更");
        }
        machine.setMachineName(req.getMachineName());
        machine.setMachineType(req.getMachineType());
        machine.setPlateNo(req.getPlateNo());
        machine.setRemark(req.getRemark());
        machine.setVersion(req.getVersion());
        if (machineMapper.updateById(machine) == 0) {
            throw new BusinessException("农机数据已被他人变更，请刷新后重试");
        }
        return machine;
    }

    /** 停用农机，不物理删除。 */
    @Transactional(rollbackFor = Exception.class)
    public Machine disable(Long id, MachineDisableRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_DISABLE, String.valueOf(id));

        Machine machine = requireMachine(id);
        if (MachineStatus.DISABLED.equals(machine.getStatus())) {
            throw new BusinessException("农机已处于停用状态");
        }
        machine.setStatus(MachineStatus.DISABLED);
        if (machineMapper.updateById(machine) == 0) {
            throw new BusinessException("农机数据已被他人变更，请刷新后重试");
        }
        return machine;
    }

    public Machine get(Long id) {
        return requireMachine(id);
    }

    public List<Machine> list() {
        return machineMapper.selectList(
                new LambdaQueryWrapper<Machine>().orderByAsc(Machine::getId));
    }

    private Machine requireMachine(Long id) {
        Machine machine = machineMapper.selectById(id);
        if (machine == null) {
            throw new BusinessException("农机不存在: " + id);
        }
        return machine;
    }
}
