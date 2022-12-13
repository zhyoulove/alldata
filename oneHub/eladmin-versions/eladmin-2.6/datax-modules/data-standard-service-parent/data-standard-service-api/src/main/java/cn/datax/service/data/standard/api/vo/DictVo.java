package cn.datax.service.data.standard.api.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 数据标准字典表 实体VO
 * </p>
 *
 * @author AllDataDC
 * @date 2022-11-26
 */
@Data
public class DictVo implements Serializable {

    private static final long serialVersionUID=1L;

    private String id;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    private String remark;
    private String typeId;
    private String gbTypeCode;
    private String gbTypeName;
    private String gbCode;
    private String gbName;
}
