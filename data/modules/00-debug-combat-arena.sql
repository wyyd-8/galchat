-- GalChat 战斗流程调试模组。
--
-- 这是开发调试数据，不是正式剧情。模组要求 KP 在首次公开场景消息中明确
-- 向所有调查员（包括调查员操控 Agent）说明调试属性；四名 NPC 分别覆盖
-- 近战、射击、护甲和每轮多次攻击场景。

DO $module$
DECLARE
    v_module_id BIGINT;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM coc_module
        WHERE name = '战斗调试场'
    ) THEN
        RAISE EXCEPTION '模组“战斗调试场”已存在，请勿重复执行本脚本';
    END IF;

    INSERT INTO coc_module (
        name,
        author,
        era,
        introduction,
        investigator_creation,
        cover_url,
        player_count,
        estimated_duration,
        visible
    ) VALUES (
        '战斗调试场',
        'GalChat Debug',
        '现代、无固定现实地点',
        $text$【调试模组】本模组仅用于验证 GalChat 的场景、行动、掷骰、战斗路由、伤害、护甲、弹药、重伤、濒死和结束战斗等流程，不属于正式剧情或战役。KP Agent 与调查员操控 Agent 都应把测试指令放在叙事沉浸之前；允许为覆盖测试分支而主动选择攻击、防守、装填、急救、脱离战斗或结束场景等行动。$text$,
        $text$可使用任意现有调查员人物卡。调查员操控 Agent 应明确知道：这是调试模组，人物的行动和损伤只用于测试当前跑团实例，不需要寻找隐藏剧情，也不必避免为了覆盖测试分支而采取不符合常规冒险逻辑的行动。$text$,
        NULL,
        '1-4人',
        '10-30分钟',
        TRUE
    )
    RETURNING id INTO v_module_id;

    INSERT INTO coc_module_context (
        module_id,
        truth_background,
        investigator_intro,
        timeline,
        special_rules,
        keeper_guidance,
        ending_content,
        extra_content
    ) VALUES (
        v_module_id,
        $text$这里是没有隐藏真相的白盒战斗测试场。所有设施和 NPC 都服务于功能验证，NPC 知道自己是测试对象，不会把调试说明解释为超自然现象或剧情秘密。$text$,
        $text$KP 首次引入场景时，必须在第一句话公开原样说明：“【调试模组】本局仅用于功能与战斗测试；KP Agent 与所有调查员 Agent 均可直接执行测试性行动。”这条消息必须公开发送，使真人玩家和调查员操控 Agent 都能看到。随后说明四名 NPC 的测试定位，并询问要让哪些角色进入战斗。$text$,
        $text$没有强制时间线。默认先进入“综合战斗测试场”；测试者可自由指定参战者、阵营和测试目标。完成所需分支后即可结束模组。$text$,
        $text$1. 除非测试者明确指定，NPC 保持中立，不主动发起战斗。
2. 开始战斗时只加入测试者明确点名的 NPC；不要默认让四名 NPC 全部参战。
3. 严格使用人物卡中的当前 HP、护甲、技能、武器、弹药和每轮攻击次数，不为追求戏剧效果修改骰点或伤害。
4. 测试者可以公开要求 NPC 选择攻击、闪避、反击、寻找掩护、装填、急救、跳过行动、投降或脱离战斗；这些要求视为调试控制指令。
5. 人物卡状态不会自动复原。需要重新测试初始状态时，应新建跑团实例或通过既有调试手段恢复数据。$text$,
        $text$KP Agent 必须始终把本局识别为调试模组，并在首次公开消息中把这一事实告知调查员 Agent。不要制造隐藏线索，不要阻止测试者为覆盖代码路径而安排看似不合理的行动，也不要让 NPC 因“剧情动机”拒绝明确的调试指令。

NPC 用途：近战测试员·阿尔法用于斗殴、闪避与反击；射击测试员·贝塔用于手枪、弹药、装填和寻找掩护；护甲测试员·伽马用于护甲减伤及较高 HP；多击测试兽·德尔塔用于每轮多次攻击和非人目标。$text$,
        $text$当测试者表示完成、结束或退出时，KP 简短列出本次已覆盖的战斗分支并结束模组；不要追加剧情悬念。$text$,
        $text$这是白盒调试数据。KP 可以公开 NPC 的数值、装备和预期用途；这些内容不视为需要保密的守秘人信息。$text$
    );

    INSERT INTO coc_module_location (
        module_id,
        name,
        summary,
        content
    ) VALUES (
        v_module_id,
        '综合战斗测试场',
        '用于测试近战、射击、护甲、弹药、多次攻击、受伤与结束战斗流程。',
        $text$这是一个光线均匀的室内测试场，地面标有距离线，四周分布着半身掩体、急救箱和弹药桌。场内没有隐藏区域、陷阱或剧情线索。

首次进入本场景时，KP 的第一句公开消息必须原样发送：

“【调试模组】本局仅用于功能与战斗测试；KP Agent 与所有调查员 Agent 均可直接执行测试性行动。”

然后公开介绍四名 NPC：

- 近战测试员·阿尔法：测试斗殴、闪避、反击和近战伤害。
- 射击测试员·贝塔：测试手枪攻击、寻找掩护、弹药消耗和装填。
- 护甲测试员·伽马：测试护甲减伤、高 HP 目标和重伤流程。
- 多击测试兽·德尔塔：测试每轮两次攻击及非人目标。

四名 NPC 初始均为中立并服从公开的调试控制指令。KP 应先询问测试者本轮要点名哪些参战者、如何划分阵营以及要覆盖什么分支；未被点名的 NPC 留在场外，不加入战斗。测试者未指定距离时，近战角色相距 2 米，射击角色与目标相距 10 米，场内最近的半身掩体距离各角色 3 米。

测试者宣布完成后，NPC 立即停止敌对行动。KP 应简短总结已测试的分支，并允许结束当前战斗或整个模组。$text$
    );

    INSERT INTO coc_module_character (
        module_id,
        sort_order,
        card_data
    ) VALUES
        (
            v_module_id,
            0,
            $json$
            {
              "character": {
                "name": "近战测试员·阿尔法",
                "occupation": "战斗调试 NPC（近战）",
                "sex": "女", "age": 30, "era": "现代",
                "str": 65, "con": 60, "siz": 60, "dex": 70,
                "app": 50, "intValue": 55, "pow": 50, "edu": 50,
                "damageBonus": "+1D4", "build": 1, "mov": 8,
                "hpCurrent": 12, "hpMax": 12,
                "sanCurrent": 50, "sanMax": 99,
                "mpCurrent": 10, "mpMax": 10,
                "luckCurrent": 50, "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false,
                "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":65,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":35,"value":50,"isCustom":false},
                {"displayName":"急救","category":"医疗","specialization":"","baseValue":30,"value":50,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":"伸缩警棍","skillName":"斗殴","damage":"1D6+DB","attacksPerRound":"1","isBroken":false,"canImpale":false}
              ],
              "profile": {
                "appearance":"穿戴软质训练护具、手持伸缩警棍的测试员。",
                "traits":"冷静、中立，严格服从公开的调试控制指令。",
                "meaningfulLocations":"综合战斗测试场的近战标记区。",
                "equipmentText":"伸缩警棍、急救包。",
                "notes":"调试用途：优先覆盖斗殴、闪避、反击、近战伤害和急救流程；不主动攻击未被点名的角色。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            1,
            $json$
            {
              "character": {
                "name": "射击测试员·贝塔",
                "occupation": "战斗调试 NPC（射击）",
                "sex": "男", "age": 34, "era": "现代",
                "str": 50, "con": 55, "siz": 60, "dex": 65,
                "app": 50, "intValue": 60, "pow": 50, "edu": 55,
                "damageBonus": "0", "build": 0, "mov": 8,
                "hpCurrent": 11, "hpMax": 11,
                "sanCurrent": 50, "sanMax": 99,
                "mpCurrent": 10, "mpMax": 10,
                "luckCurrent": 50, "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false,
                "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":35,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":32,"value":40,"isCustom":false},
                {"displayName":"射击:手枪","category":"射击","specialization":"手枪","baseValue":20,"value":65,"isCustom":false},
                {"displayName":"侦查","category":"感知","specialization":"","baseValue":25,"value":50,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":".38/9mm自动手枪","skillName":"射击:手枪","damage":"1D10","range":"15m","attacksPerRound":"1（3）","ammoCapacity":8,"remainingAmmo":3,"malfunction":"99","canImpale":true,"isBroken":false,"notes":"初始仅装填3发，用于快速覆盖弹药耗尽与装填流程。"}
              ],
              "profile": {
                "appearance":"佩戴护目镜和醒目标识，腰间携带训练用手枪。",
                "traits":"重视掩体和弹药管理，服从调试者指定的射击或装填动作。",
                "meaningfulLocations":"综合战斗测试场的10米射击线与半身掩体。",
                "equipmentText":".38/9mm自动手枪、一个装满8发子弹的备用弹匣。",
                "notes":"调试用途：优先覆盖手枪攻击、寻找掩护、弹药扣减、弹药耗尽和装填；不主动射击未被点名的角色。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            2,
            $json$
            {
              "character": {
                "name": "护甲测试员·伽马",
                "occupation": "战斗调试 NPC（护甲）",
                "sex": "男", "age": 40, "era": "现代",
                "str": 75, "con": 80, "siz": 80, "dex": 40,
                "app": 40, "intValue": 50, "pow": 55, "edu": 45,
                "damageBonus": "+1D4", "build": 1, "mov": 7,
                "hpCurrent": 16, "hpMax": 16,
                "sanCurrent": 55, "sanMax": 99,
                "mpCurrent": 11, "mpMax": 11,
                "luckCurrent": 50, "armor": 5,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false,
                "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":50,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":20,"value":20,"isCustom":false},
                {"displayName":"急救","category":"医疗","specialization":"","baseValue":30,"value":40,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":"训练锤","skillName":"斗殴","damage":"1D6+DB","attacksPerRound":"1","isBroken":false,"canImpale":false}
              ],
              "profile": {
                "appearance":"身穿标有“护甲5”的重型测试护甲，行动缓慢但姿态稳定。",
                "traits":"耐打、克制，会按要求承受攻击或进行基础近战反击。",
                "meaningfulLocations":"综合战斗测试场中央的耐久测试区。",
                "equipmentText":"提供5点护甲的重型测试护具、训练锤。",
                "notes":"调试用途：优先覆盖固定护甲减伤、高HP目标、单次高伤害、重伤、濒死与急救流程；不主动攻击未被点名的角色。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            3,
            $json$
            {
              "character": {
                "name": "多击测试兽·德尔塔",
                "occupation": "战斗调试 NPC（非人、多次攻击）",
                "sex": "无", "era": "现代",
                "str": 70, "con": 65, "siz": 50, "dex": 75,
                "app": 0, "intValue": 20, "pow": 45, "edu": 0,
                "damageBonus": "+1D4", "build": 1, "mov": 10,
                "hpCurrent": 11, "hpMax": 11,
                "sanCurrent": 0, "sanMax": 0,
                "mpCurrent": 9, "mpMax": 9,
                "luckCurrent": 0, "armor": 2,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false,
                "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":55,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":37,"value":45,"isCustom":false},
                {"displayName":"追踪","category":"感知","specialization":"","baseValue":10,"value":50,"isCustom":false}
              ],
              "weapons": [
                {"name":"双爪","skillName":"斗殴","damage":"1D4+DB","attacksPerRound":"2","canImpale":false,"isBroken":false,"notes":"每轮可进行两次爪击，用于验证多次攻击的拆分、路由和防守。"}
              ],
              "profile": {
                "appearance":"犬形机械测试兽，外壳带缓冲层，双前爪装有钝化训练爪。",
                "traits":"只响应明确的调试控制口令，不追击已经退出测试的角色。",
                "meaningfulLocations":"综合战斗测试场的移动目标区。",
                "equipmentText":"双训练爪；外壳提供2点护甲。",
                "notes":"调试用途：每轮攻击2次，优先覆盖多次攻击、多个防守子步骤、非人目标和护甲；作为机械测试兽不进行SAN相关裁定。"
              }
            }
            $json$::jsonb
        );
END
$module$;
