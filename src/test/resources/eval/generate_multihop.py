#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
多跳（数据键桥接）语料生成器 —— batch5

构造原理（与"盲指针"那轮的区别）：
  判别性 token（设备型号）**不在问题里**，只能从第一跳文档（采购通知）里读到。
  因此：
    - 任何排序函数都无法把"正确的保修条款"与其余同型号条款区分开（它们对问题相关度相同）；
    - 调大 K 无法替代迭代——把 300 条条款全塞进上下文，模型仍不知道该信哪条。

文档结构：
  A. 采购通知（1 篇）：把"人可读名称 + 部门"与"型号"配对 —— 桥接文档
  B. 按型号的保修条款（300 篇）：标题与正文**只写型号**，不含通用名称 —— 第二跳目标
  C. 通用管理办法（3 篇）：真实感补充

确定性：固定 SEED，period 由型号哈希决定，重跑结果完全一致。
"""
import hashlib
import random
from pathlib import Path

SEED = 20261014
OUT = Path(__file__).parent / "corpus" / "gen" / "batch5"

# ---------------------------------------------------------------- 采购通知明细
# (品类, 人可读名称, 型号, 数量, 配发部门)
NOTICE_DEVICES = [
    ("笔记本电脑", "MacBook Pro 14 寸", "MRX33CH/A", 40, "研发中心"),
    ("笔记本电脑", "MacBook Pro 16 寸", "MRW13CH/A", 12, "设计部"),
    ("笔记本电脑", "ThinkPad X1 Carbon Gen 12", "21KC-S1M700", 35, "产品部"),
    ("笔记本电脑", "MateBook X Pro 2026", "MRGF-X26", 20, "市场部"),
    ("笔记本电脑", "Dell Latitude 5450", "LAT5450-A2", 28, "客服中心"),
    ("笔记本电脑", "MacBook Air 13 寸", "MRXV3CH/A", 45, "行政部"),
    ("笔记本电脑", "ThinkPad T14s Gen 5", "21LS-S0A100", 30, "财务部"),
    ("笔记本电脑", "惠普战 66 六代", "HP-Z66-G6", 22, "人力资源部"),
    ("显示器", "Dell UltraSharp U2723QE", "U2723QE-27", 60, "各部门"),
    ("显示器", "LG UltraFine 27UP850", "27UP850-W", 25, "设计部"),
    ("显示器", "AOC Q27G3XMN", "Q27G3XMN-CN", 18, "研发中心"),
    ("显示器", "明基 PD2705U", "PD2705U-B", 15, "设计部"),
    ("显示器", "联想 ThinkVision T27h", "T27H-30", 40, "各部门"),
    ("服务器", "Dell PowerEdge R760", "PER760-2U", 4, "数据中心"),
    ("服务器", "浪潮 NF5280M7", "NF5280M7-S", 6, "数据中心"),
    ("服务器", "联想 ThinkSystem SR650", "SR650V3-B", 3, "数据中心"),
    ("服务器", "华为 FusionServer 2288H", "2288H-V6", 5, "数据中心"),
    ("网络设备", "华为交换机 S5735", "S5735-L48T4X", 12, "数据中心"),
    ("网络设备", "H3C 无线控制器 WX3520X", "WX3520X-AP", 8, "数据中心"),
    ("网络设备", "飞塔防火墙 FG-100F", "FG100F-BDL", 4, "信息安全部"),
    ("网络设备", "思科路由器 ISR4331", "ISR4331-K9", 6, "数据中心"),
    ("打印设备", "惠普 LaserJet M479fdw", "M479FDW-A", 10, "各部门"),
    ("打印设备", "佳能 iR-ADV C3830", "C3830i-A", 3, "行政部"),
    ("打印设备", "兄弟 HL-L8360CDW", "HL8360CDW", 8, "财务部"),
    ("移动设备", "iPhone 16 Pro", "A3102-CN", 15, "销售部"),
    ("移动设备", "华为 Mate 70 Pro", "MATE70P-CN", 20, "销售部"),
    ("移动设备", "小米 15 Ultra", "MI15U-CN", 12, "市场部"),
    ("移动设备", "iPad Pro 13 寸", "MVX23CH/A", 18, "设计部"),
    ("平板设备", "华为 MatePad Pro 13.2", "MTP13-CN", 10, "产品部"),
    ("平板设备", "Surface Pro 11", "SP11-2IN1", 8, "市场部"),
    ("外设", "罗技 MX Master 3S", "MXM3S-B", 80, "各部门"),
    ("外设", "明基 ScreenBar Halo", "SCHALO-B", 50, "各部门"),
]

# ---------------------------------------------------------------- 型号池（含干扰）
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PREFIXES = ["LT", "MN", "SV", "NW", "PR", "MB", "TB", "AC"]


def gen_extra_codes(count: int, rng: random.Random) -> list:
    """生成不在采购通知里的历史设备型号（纯干扰，均匀分布在各品类）。"""
    codes = []
    while len(codes) < count:
        p = rng.choice(PREFIXES)
        body = "".join(rng.choice(CODE_ALPHABET) for _ in range(4))
        tail = rng.choice(["-A", "-B", "-C", "-CN", "-2U", ""])
        code = f"{p}{body}{tail}"
        if code not in {d[2] for d in NOTICE_DEVICES} and code not in codes:
            codes.append(code)
    return codes


# 保修期限组合（整机, 电池, 显示屏）——必须足够多样，使答案无法被猜中
PERIODS = [
    (24, 12, 12),
    (36, 12, 24),
    (48, 24, 24),
    (36, 18, 24),
    (24, 24, 12),
    (60, 24, 36),
    (12, 12, 6),
    (36, 24, 12),
]


def period_of(code: str):
    """型号 → 保修期限（确定性；与 SEED 绑定）"""
    h = hashlib.sha256(f"{SEED}:{code}".encode()).hexdigest()
    return PERIODS[int(h[:8], 16) % len(PERIODS)]


def clause_doc(code: str, year: int, month: int) -> str:
    p1, p2, p3 = period_of(code)
    return f"""# 设备保修条款（型号 {code}）

> 适用范围：北京总部及各子公司。

（云启科技 · {year} 年 {month} 月修订）

## 一、保修期限

型号 {code} 的保修期自领用登记之日起算：整机 {p1} 个月，电池 {p2} 个月，显示屏 {p3} 个月。
超出上述期限的维修，按有偿服务处理，费用由使用部门承担。

## 二、保修范围

正常使用条件下发生的硬件故障属于保修范围。下列情形不在保修范围内：

1. 人为损坏、进液、摔碰导致的故障；
2. 未经信息技术部书面同意私自拆机或送第三方维修；
3. 因非原厂配件导致的连带故障。

## 三、报修方式

出现故障时，通过 OA 系统「IT 服务」模块提交报修工单，注明设备型号 {code} 与资产编号。
信息技术部应在 2 个工作日内响应，需返厂的设备由信息技术部统一安排寄修。
"""


def notice_doc() -> str:
    lines = [
        f"{i}. {cat}：{name}（型号 {code}）{qty} 台，配发{dep}；"
        for i, (cat, name, code, qty, dep) in enumerate(NOTICE_DEVICES, 1)
    ]
    return f"""# 2026 年第一季度办公设备采购通知

> 适用范围：北京总部及各子公司。

（云启科技 · 2026 年 3 月 15 日 · 信息技术部）

## 一、采购背景

为支持研发与办公环境升级，公司于 2026 年第一季度完成一批办公设备采购。
现将采购明细与配发情况通知如下，请各部门按本通知办理领用登记。

## 二、采购与配发明细

{chr(10).join(lines)}

## 三、领用与登记

各部门负责人应于 2026 年 4 月 10 日前组织完成领用登记，登记后由信息技术部录入固定资产系统。
设备保修期自领用登记之日起算；**各型号的具体保修期限，见对应型号的《设备保修条款》**，本通知不另行规定。

## 四、资产编号规则

资产编号按「YC-品类代码-年份-流水号」编制，例如 YC-LT-2026-0041。
资产编号与设备型号的对应关系以固定资产系统记录为准。

## 五、咨询与异常处理

设备配置、领用登记与保修问题，请联系信息技术部；涉及采购价格与供应商的问题，请联系采购部。
"""


MISC_DOCS = {
    "设备保修与维修管理办法.md": """# 设备保修与维修管理办法

> 适用范围：北京总部及各子公司。

（云启科技 · 2026 年 1 月修订）

## 一、总则

为规范公司办公设备的保修与维修管理，明确各方职责，制定本办法。
本办法适用于公司采购的各类办公设备，包括笔记本电脑、显示器、服务器、网络设备、打印设备与移动设备。

## 二、保修期限的确定

设备的保修期限**按型号分别规定**，具体见各型号对应的《设备保修条款》。
不同型号的整机、电池与显示屏保修期限可能不同，不得相互比照适用。

## 三、维修流程

1. 使用人通过 OA 系统「IT 服务」模块提交报修工单，注明设备型号与资产编号；
2. 信息技术部核验保修状态：在保修期内的，按保修流程处理；超出保修期的，转有偿服务；
3. 需返厂维修的，由信息技术部统一寄修并跟踪进度；
4. 维修完成后，使用人在工单中确认验收。

## 四、职责分工

信息技术部负责保修状态核验、返厂寄修与进度跟踪；使用部门负责设备日常保管；
财务部负责有偿服务费用的审核与结算。
""",
    "固定资产领用登记规定.md": """# 固定资产领用登记规定

> 适用范围：北京总部及各子公司。

（云启科技 · 2025 年 12 月修订）

## 一、登记时限

员工领用办公设备后，应在 5 个工作日内完成固定资产领用登记。
批量采购的设备，由使用部门负责人在通知规定的期限内统一组织登记。

## 二、登记内容

登记内容包括：设备名称、型号、资产编号、使用人、领用日期与存放地点。
**保修期自领用登记之日起算**，因此登记日期影响设备的保修起算时点，务必如实填写。

## 三、资产变更

员工转岗、调动或离职时，应办理资产移交或退还手续，并在固定资产系统中更新使用人信息。
设备在部门之间调剂的，由信息技术部更新存放地点与使用部门。
""",
    "IT服务工单处理时限.md": """# IT 服务工单处理时限

> 适用范围：北京总部及各子公司。

（云启科技 · 2026 年 2 月修订）

## 一、响应时限

| 工单类型 | 响应时限 | 解决时限 |
|---|---|---|
| 设备故障报修 | 2 个工作日 | 10 个工作日 |
| 账号与权限 | 1 个工作日 | 3 个工作日 |
| 网络故障 | 4 小时 | 2 个工作日 |

## 二、加急处理

影响生产或客户交付的工单可申请加急，需由部门负责人确认，加急工单响应时限为 2 小时。

## 三、超期处理

超过解决时限未完成的工单，信息技术部应在工单中说明原因与预计完成时间。
""",
}


def main():
    rng = random.Random(SEED)
    OUT.mkdir(parents=True, exist_ok=True)

    # A. 采购通知
    (OUT / "M00-2026年第一季度办公设备采购通知.md").write_text(notice_doc(), encoding="utf-8")

    # B. 按型号的保修条款：通知内型号 + 历史干扰型号
    notice_codes = [d[2] for d in NOTICE_DEVICES]
    extra_codes = gen_extra_codes(268, rng)
    all_codes = notice_codes + extra_codes

    written = 0
    for i, code in enumerate(all_codes):
        year = 2024 + (int(hashlib.sha256(f"{SEED}:y:{code}".encode()).hexdigest()[:4], 16) % 3)
        month = 1 + (int(hashlib.sha256(f"{SEED}:m:{code}".encode()).hexdigest()[:4], 16) % 12)
        safe = code.replace("/", "-")   # 型号中的 / 不能进文件名，仅文件名转义
        (OUT / f"M{i+1:03d}-设备保修条款-{safe}.md").write_text(clause_doc(code, year, month), encoding="utf-8")
        written += 1

    # C. 通用管理办法
    for name, text in MISC_DOCS.items():
        (OUT / f"Z-{name}").write_text(text, encoding="utf-8")

    print(f"生成完成: {OUT}")
    print(f"  采购通知 1 篇（含 {len(NOTICE_DEVICES)} 个型号）")
    print(f"  保修条款 {written} 篇（通知内 {len(notice_codes)} + 历史干扰 {len(extra_codes)}）")
    print(f"  通用办法 {len(MISC_DOCS)} 篇")
    print(f"  合计 {1 + written + len(MISC_DOCS)} 篇")


if __name__ == "__main__":
    main()
