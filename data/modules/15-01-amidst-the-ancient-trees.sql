-- 《第15章 模组》的第一个模组：《古树林中》（Amidst the Ancient Trees）。
--
-- 本脚本按 GalChat 当前模组聚合结构整理内容，而非将 Markdown 整章塞入单一字段：
--   * 全局真相、开场、时间线、规则和结局写入 coc_module_context；
--   * 可探索场景写入 coc_module_location；
--   * 可独立查询的重要证据写入 coc_module_clue；
--   * 四份梦唤文字材料写入 coc_module_material；
--   * 为本章给出完整属性的 NPC，以及第十四章明确引用的怪物建立人物卡。
--
-- 原文没有提供材料图片或封面资源，因此 cover_url 为 NULL，material.image_url 使用空字符串。
-- 格拉基、格拉基之仆和美洲黑熊的数据来自第十四章对应条目；缺失的 APP、EDU 等
-- 人类专用属性统一记为 0，并在人物卡备注中标明，不臆造技能或职业数值。

DO $module$
DECLARE
    v_module_id BIGINT;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM coc_module
        WHERE name = '古树林中'
    ) THEN
        RAISE EXCEPTION '模组“古树林中”已存在，请勿重复执行本脚本';
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
        '古树林中',
        NULL,
        '1925年夏，美国佛蒙特州本宁顿与绿山国家森林公园',
        $text$当地实业家卢卡斯·斯特朗的女儿珍妮遭到绑架。赎金交付演变成枪战，两名幸存绑匪带着赎金逃进绿山国家森林公园，珍妮仍被囚禁在森林深处。调查员作为志愿搜查队进入森林追捕绑匪并营救人质，却逐渐发现，绑架案只是更古老威胁的表层：湖中的旧日支配者格拉基正借不死仆从寻找一块能够削弱其牢笼的水晶碎片。

本模组偏重野外行动、追踪、潜行、交涉与战斗，调查仍然能提供路线、预警和解决危机所需的关键情报。$text$,
        $text$适合为本次搜救创建调查员，也可接入既有战役。推荐技能包括追踪、侦查、聆听、潜行、格斗、射击、导航、说服和急救。任何性别的角色都可以参加搜查队。

可选个人动机：急需筹得1000美元还债；替被斯特朗不公解雇的弟弟讨回公道；借救回珍妮争取复职；趁机向从小欺凌自己的西德尼·哈里斯复仇。完成个人动机时，守秘人可在结局给予额外1D6点理智值奖励。$text$,
        NULL,
        NULL,
        NULL,
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
        $text$数千年前，格拉基随一颗流星坠落地球。流星及囚禁它的水晶牢笼在坠落时破碎，碎片散落各地；只要这些碎片仍保有力量，格拉基就无法彻底挣脱。它会把自身投射到碎片附近的水域，以“梦唤”吸引人类，再用脊刺把他们转化为不死仆从，命令他们寻找水晶并送到水边供它汲取力量。

1865年，逃避联邦军征召的乔瑟夫·特纳及奥古斯特、雅各布、文森特、路易斯在萨默赛特以北的黑湖扎营。他们响应梦唤，接受“永生”，此后六十年一直替格拉基挖掘附近的水晶。

1925年，佛蒙特水务公司计划在新萨默赛特建设水库。地基中出现无法分析的奇异矿藏，董事长卢卡斯·斯特朗担心污染与丑闻，秘密派卡尔·怀特带领詹姆斯·斯坦顿、迪恩·沃尔特斯和理查德·吉布森进入森林清除同类沉积物。外界误传勘探队在找黄金，西德尼·哈里斯遂伙同尤金·克莱顿和克里斯托弗·多布斯绑架斯特朗的16岁女儿珍妮勒索赎金。

赎金交付时，已受梦唤折磨的绑匪惊慌开枪。哈里斯带走赎金，与受伤的克莱顿逃入森林；多布斯继续在旧狩猎小屋看守珍妮。与此同时，勘探队已被格拉基转化。新旧仆从借现代设备即将挖出水晶，还袭击了六名画家和霍尔父子。格拉基的目标不是绑架案，而是在第三天日落后把水晶运到湖中，汲尽其中束缚自己的力量。$text$,
        $text$1925年6月20日上午10时，调查员参加本宁顿警察局通报会。詹金斯警长说明：哈里斯团伙索要10000美元赎金；昨夜交付时爆发枪战，两名警官中弹；哈里斯和一名同伙带钱逃进森林，警方判断珍妮仍被关在林中。斯特朗为每名志愿者每天支付25美元，并悬赏5000美元换女儿平安归来。

调查员被编入同一支搜查小队，中午出发，之前有两小时购买补给。镇上的图书馆和报社可查到南北战争逃兵曾藏入这片森林，以及斯特朗派地质队勘探稀有矿藏的报道。镇民还会谈到不愿深入森林的猎人、刚进山的画家、印第安墓地假线索、地下古老邪物和乔瑟夫·特纳的“鬼魂”。到森林边缘后，即使追踪检定连续失败，也必须让调查员最终确认两名逃犯进入森林的路线；成功检定则应换来提前察觉危险或更有利的遭遇位置。$text$,
        $text$第0天（6月19日，周五）：赎金枪战后，哈里斯与负伤的克莱顿分头深入森林。天亮前，特纳一伙袭击画家营地，抓走五名画家及布莱恩、亚瑟·霍尔；一名重伤画家逃脱。

第1天（6月20日，周六）：调查员参加通报会并进山。哈里斯继续赶往多布斯看守珍妮的藏身处；克莱顿因伤只走了一半路，午夜向路过的不死仆从开枪。夜间，仆从杀死多布斯并把珍妮带到挖掘现场。逃脱的画家因失血过多死在调查员路线附近。

第2天（6月21日，周日）：调查员可能发现画家尸体、勘探卡车和克莱顿。傍晚哈里斯抵达藏身处，发现珍妮失踪；夜里他用霰弹枪击退仆从。勘探队进行大规模爆破，找到水晶，蓝光映上云层。五名画家被带到湖畔开始转化，珍妮与霍尔父子仍被关在挖掘现场。

第3天（6月22日，周一）：哈里斯因梦唤崩溃，躲在藏身小屋。被刺穿的画家正在转化，新仆从加入挖掘工作。调查员最可能在今天抵达特纳小屋、湖畔或挖掘现场。若无人阻止，仆从将在日落后把水晶拖入湖中，让格拉基汲取其力量，再把剩余俘虏转化。$text$,
        $text$梦唤：第一夜进行POW检定；失败者随机获得一份梦唤材料并承受其中效果。第二夜所有调查员都会受到梦唤，且不能再抽到“不眠之夜”。梦境或现实中的相同意象可再次触发理智检定，但同一来源的累计理智损失仍受通常上限约束。

野外推进：追踪失败可以拖慢队伍并引入熊、误射、遗失装备、受伤、误入卡车路线或遭遇落单仆从等后果，但不能切断唯一主线。成功的追踪、侦查或聆听应提供预警和位置优势。

时间压力：模组按三天组织，但守秘人可根据玩家路线调整遭遇先后。第二夜爆破与第三日日落后的运晶行动是主要时钟；离开森林求援会给仆从完成任务和炸毁现场的机会。

格拉基之仆：他们保有思考、说话、欺骗和战术能力，并非无脑僵尸。特纳等老仆从在日光下暴露一小时后行动承受一颗惩罚骰，持续三小时会灰飞烟灭；蓝色石棺休眠可缓解绿腐。新转化的勘探队员暂时不受日光伤害。$text$,
        $text$这是偏行动的新手模组。不要因关键追踪失败令故事停摆，应把失败转化为代价；同时让成功检定确实改善处境。特纳和勘探队都能装成人类并布置圈套，哈里斯与克莱顿则会因恐惧、伤势和疯狂采取不同反应。

调查员可以沿绑匪足迹、画家血迹、卡车轮迹、拖拽痕迹或特纳旧路推进，路线不必固定。允许他们绕行侦察、营救俘虏、破坏滑轮、利用炸药、打碎水晶或削减仆从人数。若调查员被俘，保留从绳索、棚屋或石棺逃脱的机会。

如需提高威胁，可给南北战争时期的不死仆从配备仍能使用的旧枪，但应提前通过特纳小屋的制服与步枪等信息进行预示。$text$,
        $text$若仆从把水晶交给格拉基，格拉基便削弱了牢笼的一部分；它尚未自由，但离目标更近。知道俘虏所在却未能营救时，每名调查员损失1/1D6点理智值。

若珍妮获救且格拉基的计划受阻，完成个人动机的调查员各恢复1D6点理智值。若俘虏获救，调查员除赏金外各恢复1D6点理智值，并成为本宁顿当地名人。即使无法彻底摧毁水晶，破坏运送、把它打成难以搬运的小块或消灭足够多的仆从，也可以延迟格拉基的计划。

若调查员离开现场求援后返回，仆从可能已经用剩余炸药摧毁棚屋与挖掘现场，并沿泥地足迹进入湖中，只留下难以公开解释的废墟。$text$,
        $text$主要角色：卢卡斯·斯特朗（雇主与人质之父）；珍妮·斯特朗（16岁人质）；西德尼·哈里斯、尤金·克莱顿、克里斯托弗·多布斯（三名绑匪）；卡尔·怀特、詹姆斯·斯坦顿、迪恩·沃尔特斯、理查德·吉布森（已转化的勘探队）；阿利斯泰尔与乔治·罗森、布莱恩与亚瑟·霍尔（两对猎人父子）；乔瑟夫·特纳与四名南北战争逃兵仆从；六名外出采风的画家；湖中格拉基化身。

外部数据引用已经展开为人物卡：奥古斯特、雅各布、文森特、路易斯，以及卡尔、詹姆斯、迪恩、理查德分别使用第十四章“格拉基之仆”的标准属性；格拉基使用第十四章旧日支配者条目；森林中的可选黑熊遭遇使用第十四章“美洲黑熊”条目。外部条目未提供的 APP、EDU 等人类专用属性以0适配运行时必填字段，不把这些补位值解释为原书设定。$text$
    );

    INSERT INTO coc_module_location (
        module_id,
        parent_location_id,
        name,
        summary,
        content
    ) VALUES
        (
            v_module_id,
            NULL,
            '本宁顿镇与警察局',
            '通报会、报酬谈判、出发前采购，以及报刊和镇民提供的初步信息。',
            $text$上午10时，詹金斯警长在警察局召集志愿者，公开绑架、赎金枪战、两名逃犯和珍妮仍在林中的判断。卢卡斯·斯特朗承诺每人每天25美元，并重申5000美元救援悬赏；他甚至暗示不介意哈里斯以尸体形式回来。调查员可追问、议价或挑战斯特朗，但警长会制止过度冲突。

队伍中午集合，出发前有两小时采购。普通武器与野营物资可在中央大街的五金与枪械店获得，罕见物品需幸运检定且可能根本买不到。

图书馆和《本宁顿旗帜报》档案无需检定即可提供两组报道：南北战争逃兵曾把森林当作北逃加拿大的藏身地；斯特朗最近派地质勘探队前往未来水库河床寻找稀有矿藏。向镇民打听还能得知猎人避开森林中心、波士顿画家近日入山、卡车驶向北方，以及乔瑟夫·特纳鬼魂等流言。印第安墓地属于干扰信息。$text$
        ),
        (
            v_module_id,
            NULL,
            '森林追踪路线',
            '贯穿三日的野外行进区域，包含猎人遭遇、扎营、梦唤和第二夜爆破。',
            $text$森林边缘能找到哈里斯与克莱顿留下的足迹。追踪检定控制速度和遭遇优势，但最终必须让队伍取得方向。

第一天下午，调查员会遇到阿利斯泰尔·罗森和14岁的乔治。若队伍没有鲜艳服装且未提前通过聆听、侦查发现他们，猎人可能误认目标；团体幸运失败使一名调查员被擦伤1D3 HP。父子会说明同伴布莱恩与12岁的亚瑟数日前深入森林后失踪，布莱恩戴宽牛仔帽；他们还遇到过六名波士顿画家。罗森父子都做过噩梦，阿利斯泰尔记得溺水。

第一夜营地基本安全。POW失败者受到梦唤；午夜聆听成功可听到远处卡车或克莱顿开枪。执意夜行需困难CON，失败者困倦得无法继续。

第二天越深入森林，动物痕迹和声音越少。追踪失败的孤注一掷后果可选：黑熊、其他搜查队误射、差点被卡车撞上、落单仆从、遗失步枪或自然环境伤害。第二夜所有人都会梦唤；极限侦查成功可发现路易斯监视。路易斯试图回营报告，被逼入绝境时会借一次重击装死后逃走。午夜北方爆炸，云底短暂映出蓝光；随后一支被噩梦吓坏的搜查队会撤回镇上。第三天临近湖区时，树林病态发黄且死寂，聆听成功者因意识到没有任何野生动物而损失0/1 SAN。$text$
        ),
        (
            v_module_id,
            NULL,
            '画家尸体发现处',
            '第二天由血迹引出的支线，连接画家营地并提供不死仆从活动证据。',
            $text$侦查成功可见一道横穿小路的血迹，追踪成功可见散乱落叶和折枝，表明有人负伤逃跑。沿血迹偏离主路约800米，会先发现一把古旧、锈蚀且沾血的猎刀，再发现一名衣服染有颜料的男尸。未习惯死亡者见其惨状损失0/1D3 SAN。

死者没有身份证件，侧腹刀伤被草率包扎。医学或急救成功可判断他持续失血约一天后死亡。沿血迹反向追踪需要耗费当天余下大部分时间，最终抵达画家营地；守秘人可把卡车或克莱顿遭遇放在前后任一时点。$text$
        ),
        (
            v_module_id,
            NULL,
            '勘探卡车道路',
            '通往挖掘现场的深轮胎印，以及运送炸药的詹姆斯·斯坦顿。',
            $text$地面深轮迹蜿蜒向东北，导航成功可判断它通往萨默赛特以北。随后驶来的卡车由刚被转化的工程师詹姆斯·斯坦顿驾驶。他胸口有格拉基脊刺留下的伤口，以外套遮住；他冷漠、敌视但仍能正常交谈和欺骗。

斯坦顿反对搜车却不会立刻动武。车上没有逃犯，只有数箱炸药。他声称自己受斯特朗指示进行地质勘探，领班是卡尔·怀特；听说附近有人时会显得格外关注。他不会主动载人去营地，却会指明沿轮迹直走即可抵达挖掘现场。$text$
        ),
        (
            v_module_id,
            NULL,
            '尤金·克莱顿的山脊',
            '第二天黄昏与受伤绑匪对峙、追逐或审讯的地点。',
            $text$困难侦查成功可看见步枪瞄准镜反光；团体幸运决定克莱顿是否先发现队伍。若占先，他会朝头顶鸣枪警告，此后只剩三发子弹。调查员可隐蔽，潜行成功才能安全接近；正面暴露进攻会遭实弹射击。克莱顿因警察霰弹枪伤势，攻击与追逐检定都承受一颗惩罚骰。

弹药耗尽或调查员逼近后，他会逃而非死战。追逐可经过泥坡、溪流和灌木，需要攀爬、跳跃、DEX、侦查或追踪。被捕后，克莱顿投降并要求医疗。他发烧且恐惧，声称前夜明明击中一个人，对方却继续走动。他会把罪责推给哈里斯，确认枪战前珍妮仍健康，并给出多布斯看守珍妮的藏身小屋方位。$text$
        ),
        (
            v_module_id,
            NULL,
            '画家营地',
            '被袭击的六人营地，留有梦境笔记、预示性油画和通向挖掘现场的拖痕。',
            $text$营地靠近卡车路线。篝火已灭、帐篷破裂，现场有明显打斗痕迹。六名画家中一人负伤逃走并死在别处，另外五人被特纳一伙抓走。

四顶住宿帐篷之外还有公共厨房帐篷和画室帐篷。搜索可得画家笔记本、画板和未完成油画。笔记说明他们来自波士顿，最近不断做与调查员相似的噩梦，也遇到同样受梦困扰的布莱恩、亚瑟·霍尔。

油画包括普通森林景物，也包括三幅梦境意象：黄叶覆盖的小径、门内站着黑影的破旧木屋、黑湖中升起恐怖存在。经历过对应梦境者观看相同画面时再次检定SAN，损失与原梦相同，但同一来源累计损失不超过原骰最大值。

侦查成功会在灌木中找到步枪与布莱恩的宽牛仔帽，旁边有朝北、通往挖掘现场的拖拽痕迹。这里可以继续追踪袭击者去湖畔，也可转向克莱顿提供的藏身处。$text$
        ),
        (
            v_module_id,
            NULL,
            '绑匪藏身小屋',
            '珍妮原先被囚禁的旧狩猎小屋，多布斯惨死，哈里斯在屋内疯狂抵抗。',
            $text$空地中央是一座门窗紧闭的旧木屋。恶臭来自旁边浸血的树：克里斯托弗·多布斯被树枝贯穿胸膛挂在半空，目睹者损失0/1D4 SAN。木屋遍布由内向外射出的弹孔。

西德尼·哈里斯因连续梦唤与仆从袭击陷入疯狂，持霰弹枪藏在屋内。听见靠近便开火，并喊“把她还回来”。除非被制服，或调查员通过极限说服、取悦、恐吓让他冷静，否则他会把来者都视作怪物持续射击。镇定后，他会说明自己回来时珍妮已失踪、多布斯已死，昨夜又有死人袭击木屋，随后彻底崩溃。$text$
        ),
        (
            v_module_id,
            NULL,
            '特纳的小屋',
            '南北战争逃兵的旧居，地下蓝色石棺保存格拉基脊刺与揭示真相的日记。',
            $text$通往湖区的踪迹分成新旧两路：近期频繁使用的路去挖掘现场，黄叶覆盖的旧路经过特纳小屋再到湖畔。做过“小径”或“小屋”梦境者认出实景时分别损失0/1或1/1D6 SAN。

屋内有联邦军制服帽子、染血猎刀、悬挂的动物尸体和一支锈死的旧步枪。侦查成功可发现地板缝透出浅蓝光及通往土砌地下室的活板门。地下有五具发光蓝色石棺，老仆从白日休眠于此。

石棺中可找到南北战争时期私人物品、非地球金属质感的中空脊刺、带乔瑟夫·特纳名字的日记，以及无法辨识的绿色腐败粉尘。科学研究脊刺会因认识到其非地球性质损失0/1 SAN。日记记载逃兵在黑湖接受永生、格拉基被分散水晶囚禁，以及仆从必须把附近碎片送到湖中削弱牢笼。

白天奥古斯特、雅各布睡在其中两具石棺。被惊醒后，他们先以永生诱骗调查员，若遭攻击或调查员欲离开才试图制服并塞进空棺。石棺魔法封闭，内外打开都需困难STR或POW，取较低者；孤注一掷失败可导致昏迷或梦境侵袭。两名仆从不会在白天追出小屋，但会在夜间追捕逃亡者。$text$
        ),
        (
            v_module_id,
            NULL,
            '黑湖湖畔',
            '格拉基显现与转化牺牲品的地点，也是阻止水晶被汲取的最终目标之一。',
            $text$黄叶旧路抵达漆黑、恶臭、死寂的湖。五名幸存画家被绑在离岸约五米的柱上，胸前各插有约60厘米长的金属脊刺，满身血肉却仍活动。目睹惨状损失1/1D6 SAN，发现他们还活着再损失1/1D4。

画家精神已毁，但能说明活尸把他们从营地抓到挖掘现场，那里还关着一个男人、男孩和女孩；仆从正在用工具与炸药从坑中取出某物；湖中怪物把刺插进他们体内。拔刺会让画家尖叫死亡并结束痛苦。随后进行团体幸运，失败则惊动詹姆斯和迪恩前来。

被仆从发现的调查员会被制伏并送往储藏棚，或绑到湖边柱上。挣脱需困难STR或DEX。日落仍在柱上时，特纳一伙会召唤格拉基转化他们；未被解脱的画家将在午夜成为新仆从。

若仆从成功运来水晶，格拉基从水中升起并汲取蓝色水晶的力量。目睹者按第十四章格拉基条目承受理智损失。破坏运送系统、打碎水晶、消灭工人或及时营救俘虏，都能延迟或阻止这一幕。$text$
        ),
        (
            v_module_id,
            NULL,
            '水晶挖掘现场',
            '勘探队营地、五间棚屋、俘虏牢房、炸药库与水晶坑构成的终局场景。',
            $text$卡车路在五杆门处由理查德·吉布森把守。他能礼貌地假装普通工程师，以爆破危险为由拒绝公众进入。话术、说服、取悦或恐吓成功可获准见领班卡尔·怀特；失败被赶走，孤注一掷失败可能引来更多仆从并爆发冲突。

空地中央是爆破大坑。卡尔、詹姆斯、迪恩在坑与工具棚间搬运绳索和设备。五间棚屋依次为：领班办公室（勘探图、实验设备、异常矿物及污染文件）；住宿棚；废弃厨房杂物棚；窗户封死、标作“储藏室”的俘虏牢房；存放钻机、工具和炸药的工具棚。

卡尔先用“发现铂金迹象”的半真解释安抚来客，再借参观把调查员引到第四棚屋。他声称展示矿样，实则让仆从关门，在黑暗中围捕。棚内关着珍妮、布莱恩与亚瑟。特纳、文森特、路易斯可能藏在隔壁避光，特纳会以支配术协助抓捕。

俘虏能提供证词：珍妮说“死人”闯入绑匪小屋、把多布斯钉死并将她带走；布莱恩说他和亚瑟遇见画家后发现大家做相同梦，袭击者谈到与新神合一，画家随后被带走。被绑调查员解绳需困难DEX，高声交谈会被守卫听见；脱身后还需潜行穿过主房间。逃到日光下时，老仆从不会追出，但新转化勘探队会追杀。

谨慎侦察可发现白天的搬运和日落前搭建的滑轮。入夜后所有仆从用卡车和滑轮拖出发光浅蓝水晶，送往湖中。调查员可趁注意力集中于水晶时救人，也可破坏滑轮、使用现场炸药、击碎水晶或消灭工人。若离开求援，返回时可能只剩被炸毁烧尽的营地和通往湖底的脚印。$text$
        );

    INSERT INTO coc_module_clue (
        module_id,
        title,
        content,
        important
    ) VALUES
        (
            v_module_id,
            '本宁顿报刊与森林流言',
            $text$报刊证实南北战争逃兵曾在此北逃，也证实斯特朗派地质队寻找稀有矿藏。镇民补充：猎人回避森林中心、画家刚刚进山、夜间有卡车驶往北方，并流传乔瑟夫·特纳鬼魂的故事。印第安墓地说法是干扰信息。$text$,
            FALSE
        ),
        (
            v_module_id,
            '画家的血迹与尸体',
            $text$横穿主路的血迹、折枝、锈蚀血刀和染有颜料的衣服表明一名画家遭旧式刀具重伤后逃跑。医学或急救可判断其失血约一天。反向追踪血迹能找到画家营地。$text$,
            FALSE
        ),
        (
            v_module_id,
            '卡车、炸药与东北轮迹',
            $text$深轮迹指向萨默赛特以北。斯坦顿驾驶的卡车装有数箱炸药，他自称为卡尔·怀特带领的斯特朗勘探队工作，并无意间给出挖掘现场的直接路线。$text$,
            TRUE
        ),
        (
            v_module_id,
            '尤金·克莱顿的供词',
            $text$克莱顿确认珍妮在赎金枪战前仍健康，提供多布斯看守人质的旧狩猎小屋方位；他还声称前夜击中一个人后，对方仍若无其事地走开。这同时指向藏身处和非人威胁。$text$,
            TRUE
        ),
        (
            v_module_id,
            '画家笔记与梦境油画',
            $text$笔记记录六名波士顿画家和霍尔父子做了相同噩梦。三幅异常油画分别描绘病态黄叶小径、门内有黑影的破屋、黑湖中升起的有刺怪物，证明梦境来自共同外力而非偶然。$text$,
            FALSE
        ),
        (
            v_module_id,
            '牛仔帽与北向拖痕',
            $text$画家营地灌木中的步枪和宽牛仔帽属于布莱恩·霍尔；旁边北向拖拽痕迹通往挖掘现场，说明失踪猎人与画家都被同一批袭击者带走。$text$,
            TRUE
        ),
        (
            v_module_id,
            '特纳日记',
            $text$日记揭示特纳一伙在1865年响应黑湖梦唤，接受格拉基赐予的不死状态。格拉基仍被散落世界各地的水晶牢笼碎片束缚，仆从正在挖出本地碎片并准备送到湖畔，让它汲取力量、削弱牢笼。$text$,
            TRUE
        ),
        (
            v_module_id,
            '非地球金属脊刺与绿色腐败物',
            $text$蓝色石棺中保存一根闪亮、中空、折断的金属脊刺，任何相关科学都无法识别其材质；研究者意识到它并非地球金属时损失0/1 SAN。棺中绿色粉尘像真菌或霉菌，但生物学、植物学无法分类，是仆从绿腐留下的残渣。$text$,
            FALSE
        ),
        (
            v_module_id,
            '挖掘办公室文件',
            $text$勘探图标出多个疑似矿藏点。文件说明某种矿物无法分析，勘探队真正任务是清除未来水库盆地内所有同类沉积物，以避免水源污染；所谓铂金只是卡尔的掩饰。$text$,
            FALSE
        ),
        (
            v_module_id,
            '俘虏证词',
            $text$珍妮目睹“死人”杀死多布斯后把她带到挖掘现场。布莱恩与亚瑟确认画家也被抓来，袭击者称他们将“与新神合而为一”，随后把画家带往湖边。证词明确指出尚存俘虏、挖掘现场与湖中献祭之间的联系。$text$,
            TRUE
        );

    -- 原始 Markdown 只包含这四份文字手册，没有可用图片地址。
    INSERT INTO coc_module_material (
        module_id,
        title,
        description,
        image_url
    ) VALUES
        (
            v_module_id,
            '梦唤：不眠之夜',
            $text$调查员在梦中被幽深树林、病态黄叶和某种注视自己的存在包围，醒来后像整夜未眠。效果：下一次睡眠前，当天所有CON检定承受一颗惩罚骰；本材料本身不要求SAN检定。$text$,
            ''
        ),
        (
            v_module_id,
            '梦唤：黑暗中的小屋',
            $text$黄叶森林中的破旧木屋打开，身穿旧式服装的苍白人影走出。领头者眼窝蔓延深绿腐败，邀请梦者服侍新神、接受永生。效果：进行SAN检定，损失1/1D6；现实中认出特纳小屋时再次按同值检定，并应用同一来源累计损失上限。$text$,
            ''
        ),
        (
            v_module_id,
            '梦唤：穿过森林的小径',
            $text$梦者独自沿铺满病态黄叶的小径走向疑似水面的黄色空地，四周无风无声；身后树枝折断，胸口突然被尖物刺入并惊醒。材料本身未指定SAN损失；现实中认出通往特纳小屋的旧路时损失0/1 SAN。$text$,
            ''
        ),
        (
            v_module_id,
            '梦唤：死水湖畔',
            $text$梦者被堵在恶臭黑湖岸边，看见带眼卷须和金属脊刺的巨大存在升出水面，随后脊刺刺入胸膛。效果：进行SAN检定，损失1D3/1D10；这一梦境预示格拉基显现与画家被转化。$text$,
            ''
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
                "name": "乔瑟夫·特纳",
                "occupation": "受格拉基眷顾的不死仆从",
                "sex": "男",
                "era": "1925年",
                "str": 70, "con": 120, "siz": 80, "dex": 55,
                "app": 0, "intValue": 65, "pow": 75, "edu": 0,
                "damageBonus": "+1D4", "build": 1, "mov": 7,
                "hpCurrent": 20, "hpMax": 20,
                "sanCurrent": 0, "sanMax": 0,
                "mpCurrent": 15, "mpMax": 15,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":50,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":27,"value":15,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":55,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":"匕首","skillName":"斗殴","damage":"1D6+1+DB","attacksPerRound":"1","isBroken":false}
              ],
              "profile": {
                "appearance":"高度腐烂、眼窝带有深绿色痕迹的南北战争逃兵，仍保有语言和欺骗能力。",
                "traits":"狡猾而狂信，格外受到格拉基恩宠；陷入绝境时优先保护或带走水晶。",
                "meaningfulLocations":"黑湖、蓝色石棺与水晶挖掘现场。",
                "equipmentText":"匕首；必要时可使用南北战争时期武器。",
                "notes":"每轮攻击1次。法术：支配术、肢体凋萎术、纳克-提特障壁创建术。目睹特纳损失1/1D8 SAN。日光下持续1小时后所有行动承受一颗惩罚骰，持续3小时会灰飞烟灭；在蓝色石棺中休息可缓解绿腐。"
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
                "name": "西德尼·哈里斯",
                "occupation": "绑匪头目",
                "sex": "男", "era": "1925年",
                "str": 70, "con": 65, "siz": 65, "dex": 62,
                "app": 50, "intValue": 50, "pow": 50, "edu": 55,
                "damageBonus": "+1D4", "build": 1, "mov": 8,
                "hpCurrent": 13, "hpMax": 13,
                "sanCurrent": 48, "sanMax": 99,
                "mpCurrent": 10, "mpMax": 10,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":40,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":31,"value":31,"isCustom":false},
                {"displayName":"射击:步枪/霰弹枪","category":"射击","specialization":"步枪/霰弹枪","baseValue":25,"value":50,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":35,"isCustom":false},
                {"displayName":"恐吓","category":"社交","specialization":"","baseValue":15,"value":50,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":"霰弹枪","skillName":"射击:步枪/霰弹枪","damage":"4D6/2D6/1D6","range":"近/中/远","isBroken":false,"notes":"伤害随距离递减，不能造成贯穿伤害。"}
              ],
              "profile": {
                "traits":"多疑、暴躁，受梦唤后把接近藏身处的人视作抓走珍妮的怪物。",
                "meaningfulLocations":"绑匪藏身小屋。",
                "equipmentText":"霰弹枪；赎金袋。",
                "notes":"第三天躲在弹孔密布的小屋内。除非被制服或遭遇极限说服、取悦、恐吓成功，否则持续开枪；冷静后会说明珍妮失踪、多布斯被杀，继而精神崩溃。"
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
                "name": "尤金·克莱顿",
                "occupation": "绑匪",
                "sex": "男", "era": "1925年",
                "str": 70, "con": 65, "siz": 65, "dex": 62,
                "app": 50, "intValue": 50, "pow": 50, "edu": 55,
                "damageBonus": "+1D4", "build": 1, "mov": 8,
                "hpCurrent": 13, "hpMax": 13,
                "sanCurrent": 48, "sanMax": 99,
                "mpCurrent": 10, "mpMax": 10,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":40,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":31,"value":31,"isCustom":false},
                {"displayName":"射击:步枪/霰弹枪","category":"射击","specialization":"步枪/霰弹枪","baseValue":25,"value":40,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":35,"isCustom":false},
                {"displayName":"恐吓","category":"社交","specialization":"","baseValue":15,"value":50,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":".30-06栓动式步枪","skillName":"射击:步枪/霰弹枪","damage":"2D6+4","remainingAmmo":3,"isBroken":false,"notes":"第二天黄昏遭遇时只剩3发子弹。"}
              ],
              "profile": {
                "injuriesAndScars":"赎金枪战中被警察霰弹枪击伤，正在发烧。",
                "traits":"胆怯且推卸责任，被逼入绝境会投降并把绑架罪责推给哈里斯。",
                "equipmentText":".30-06栓动式步枪，遭遇开始时剩3发。",
                "notes":"受伤导致攻击和追逐检定承受一颗惩罚骰。会提供珍妮藏身小屋的位置，并描述自己击中一个仍继续行走的人。"
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
                "name": "卢卡斯·斯特朗",
                "occupation": "佛蒙特水务公司董事长",
                "sex": "男", "era": "1925年",
                "str": 65, "con": 75, "siz": 75, "dex": 65,
                "app": 70, "intValue": 90, "pow": 80, "edu": 75,
                "damageBonus": "+1D4", "build": 1, "mov": 7,
                "hpCurrent": 15, "hpMax": 15,
                "sanCurrent": 80, "sanMax": 99,
                "mpCurrent": 16, "mpMax": 16,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":32,"value":32,"isCustom":false},
                {"displayName":"心理学","category":"社交","specialization":"","baseValue":10,"value":40,"isCustom":false},
                {"displayName":"取悦","category":"社交","specialization":"","baseValue":15,"value":70,"isCustom":false},
                {"displayName":"说服","category":"社交","specialization":"","baseValue":10,"value":60,"isCustom":false},
                {"displayName":"信用评级","category":"资源","specialization":"","baseValue":0,"value":80,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false}
              ],
              "profile": {
                "traits":"强势、富有、务实；为女儿焦急，也极力避免未知矿藏污染水库的消息引发丑闻。",
                "significantPeople":"被绑架的女儿珍妮·斯特朗。",
                "assetsText":"成功伐木实业家，现任佛蒙特水务公司董事长；个人资助搜查队。",
                "spendingLevel":"富裕",
                "notes":"向志愿者支付每人每天25美元，并悬赏5000美元救回珍妮。秘密命令勘探队清除未来水库区域内无法分析的矿藏。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            4,
            $json$
            {
              "character": {
                "name": "珍妮·斯特朗",
                "occupation": "被绑架的人质",
                "sex": "女", "age": 16, "era": "1925年",
                "str": 50, "con": 75, "siz": 45, "dex": 70,
                "app": 70, "intValue": 80, "pow": 65, "edu": 65,
                "damageBonus": "0", "build": 0, "mov": 9,
                "hpCurrent": 12, "hpMax": 12,
                "sanCurrent": 64, "sanMax": 99,
                "mpCurrent": 13, "mpMax": 13,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":35,"value":35,"isCustom":false},
                {"displayName":"取悦","category":"社交","specialization":"","baseValue":15,"value":60,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":50,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false}
              ],
              "profile": {
                "significantPeople":"父亲卢卡斯·斯特朗。",
                "meaningfulLocations":"先被关在绑匪藏身小屋，后被转移至挖掘现场第四棚屋。",
                "traits":"遭绑架与超自然袭击后仍能清楚说明经过。",
                "notes":"目睹格拉基之仆闯入藏身处，把多布斯钉在树上杀死，随后将她带到挖掘现场。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            5,
            $json$
            {
              "character": {
                "name": "阿利斯泰尔·罗森",
                "occupation": "猎人、父亲",
                "sex": "男", "era": "1925年",
                "str": 50, "con": 60, "siz": 65, "dex": 65,
                "app": 60, "intValue": 65, "pow": 70, "edu": 70,
                "damageBonus": "+1D4", "build": 1, "mov": 8,
                "hpCurrent": 12, "hpMax": 12,
                "sanCurrent": 70, "sanMax": 99,
                "mpCurrent": 14, "mpMax": 14,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"射击:步枪/霰弹枪","category":"射击","specialization":"步枪/霰弹枪","baseValue":25,"value":35,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":40,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":".30-06栓动式步枪","skillName":"射击:步枪/霰弹枪","damage":"2D6+4","isBroken":false}
              ],
              "profile": {
                "significantPeople":"14岁的儿子乔治；邻居布莱恩·霍尔和亚瑟·霍尔。",
                "traits":"来自波士顿，带儿子学习打猎；谨慎且因梦见溺水而不愿深入森林。",
                "equipmentText":".30-06栓动式步枪与野营装备。",
                "notes":"会请求搜查队留意失踪的布莱恩和亚瑟，并说明布莱恩戴一顶宽大的牛仔帽。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            6,
            $json$
            {
              "character": {
                "name": "布莱恩·霍尔",
                "occupation": "猎人、父亲",
                "sex": "男", "era": "1925年",
                "str": 50, "con": 60, "siz": 65, "dex": 65,
                "app": 60, "intValue": 65, "pow": 70, "edu": 70,
                "damageBonus": "+1D4", "build": 1, "mov": 8,
                "hpCurrent": 12, "hpMax": 12,
                "sanCurrent": 70, "sanMax": 99,
                "mpCurrent": 14, "mpMax": 14,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"射击:步枪/霰弹枪","category":"射击","specialization":"步枪/霰弹枪","baseValue":25,"value":35,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":40,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3+DB","attacksPerRound":"1","isBroken":false},
                {"name":".30-06栓动式步枪","skillName":"射击:步枪/霰弹枪","damage":"2D6+4","isBroken":false}
              ],
              "profile": {
                "appearance":"常戴一顶宽大的牛仔帽遮阳。",
                "significantPeople":"12岁的儿子亚瑟；邻居阿利斯泰尔和乔治·罗森。",
                "meaningfulLocations":"画家营地与挖掘现场第四棚屋。",
                "equipmentText":".30-06栓动式步枪；宽边牛仔帽。",
                "notes":"与亚瑟遇见画家后留宿，发现众人做相同噩梦；被仆从抓走后听见他们谈论与新神合一。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            7,
            $json$
            {
              "character": {
                "name": "乔治·罗森",
                "occupation": "学习打猎的少年",
                "sex": "男", "age": 14, "era": "1925年",
                "str": 35, "con": 60, "siz": 40, "dex": 75,
                "app": 60, "intValue": 70, "pow": 55, "edu": 50,
                "damageBonus": "-1", "build": -1, "mov": 8,
                "hpCurrent": 10, "hpMax": 10,
                "sanCurrent": 55, "sanMax": 99,
                "mpCurrent": 11, "mpMax": 11,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"射击:步枪/霰弹枪","category":"射击","specialization":"步枪/霰弹枪","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":37,"value":40,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":55,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3-1","attacksPerRound":"1","isBroken":false},
                {"name":".30-06栓动式步枪","skillName":"射击:步枪/霰弹枪","damage":"2D6+4","isBroken":false}
              ],
              "profile": {
                "significantPeople":"父亲阿利斯泰尔·罗森。",
                "traits":"来自波士顿，正在学习打猎；被噩梦吓到但不愿多谈。",
                "equipmentText":".30-06栓动式步枪与野营装备。",
                "notes":"第一天与父亲在森林较外侧遇见搜查队。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            8,
            $json$
            {
              "character": {
                "name": "亚瑟·霍尔",
                "occupation": "学习打猎的少年、俘虏",
                "sex": "男", "age": 12, "era": "1925年",
                "str": 35, "con": 60, "siz": 40, "dex": 75,
                "app": 60, "intValue": 70, "pow": 55, "edu": 50,
                "damageBonus": "-1", "build": -1, "mov": 8,
                "hpCurrent": 10, "hpMax": 10,
                "sanCurrent": 55, "sanMax": 99,
                "mpCurrent": 11, "mpMax": 11,
                "armor": 0,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"射击:步枪/霰弹枪","category":"射击","specialization":"步枪/霰弹枪","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":37,"value":40,"isCustom":false},
                {"displayName":"潜行","category":"行动","specialization":"","baseValue":20,"value":55,"isCustom":false}
              ],
              "weapons": [
                {"name":"徒手攻击","skillName":"斗殴","damage":"1D3-1","attacksPerRound":"1","isBroken":false},
                {"name":".30-06栓动式步枪","skillName":"射击:步枪/霰弹枪","damage":"2D6+4","isBroken":false}
              ],
              "profile": {
                "significantPeople":"父亲布莱恩·霍尔。",
                "meaningfulLocations":"画家营地与挖掘现场第四棚屋。",
                "traits":"经历袭击与囚禁后只会哭泣和来回摇晃。",
                "equipmentText":"原有.30-06栓动式步枪已遗落在画家营地附近。",
                "notes":"与父亲一同被格拉基之仆抓走，第三天仍被关在挖掘现场。"
              }
            }
            $json$::jsonb
        );

    -- 八名有名仆从共用第十四章的标准格拉基之仆属性，但各自保留模组中的职责与状态。
    INSERT INTO coc_module_character (
        module_id,
        sort_order,
        card_data
    )
    SELECT
        v_module_id,
        servant.sort_order,
        jsonb_build_object(
            'character', jsonb_build_object(
                'name', servant.name,
                'occupation', CASE
                    WHEN servant.old_servant
                        THEN '格拉基之仆、南北战争逃兵'
                    ELSE '斯特朗勘探队成员、格拉基之仆'
                END,
                'sex', '男',
                'era', '1925年',
                'str', 50, 'con', 105, 'siz', 65, 'dex', 15,
                'app', 0, 'intValue', 65, 'pow', 50, 'edu', 0,
                'damageBonus', '0', 'build', 0, 'mov', 5,
                'hpCurrent', 17, 'hpMax', 17,
                'sanCurrent', 0, 'sanMax', 0,
                'mpCurrent', 10, 'mpMax', 10,
                'armor', 0,
                'majorWound', false, 'unconscious', false,
                'dying', false, 'dead', false,
                'temporaryInsanity', false
            ),
            'skills', jsonb_build_array(
                jsonb_build_object(
                    'displayName', '斗殴',
                    'category', '格斗',
                    'specialization', '',
                    'baseValue', 25,
                    'value', 40,
                    'isCustom', false
                ),
                jsonb_build_object(
                    'displayName', '闪避',
                    'category', '战斗',
                    'specialization', '',
                    'baseValue', 7,
                    'value', 10,
                    'isCustom', false
                )
            ),
            'weapons', jsonb_build_array(
                jsonb_build_object(
                    'name', '徒手攻击',
                    'skillName', '斗殴',
                    'damage', '1D3+DB',
                    'attacksPerRound', '1',
                    'isBroken', false
                ),
                jsonb_build_object(
                    'name', '匕首或短镰刀',
                    'skillName', '斗殴',
                    'damage', '1D6+1+DB',
                    'attacksPerRound', '1',
                    'isBroken', false,
                    'notes', '模组描述为匕首或类似器具；第十四章标准条目通常使用短镰刀。'
                )
            ),
            'profile', jsonb_build_object(
                'appearance', servant.appearance,
                'traits', servant.traits,
                'meaningfulLocations', servant.locations,
                'equipmentText', '匕首或类似工具；守秘人提高威胁时可加入南北战争时期武器。',
                'notes', servant.notes || CASE
                    WHEN servant.old_servant THEN
                        ' 每轮攻击1次。目睹其活尸外貌损失1/1D8 SAN；目睹其因绿腐死亡损失1/1D10 SAN。日光等强光下暴露1小时后所有行动承受一颗惩罚骰，持续3小时会灰飞烟灭；蓝色石棺休眠可缓解绿腐。APP、EDU和SAN为外部怪物条目未提供的运行时补位值0。'
                    ELSE
                        ' 每轮攻击1次。外表仍像人类时无需损失SAN，仔细发现其不死本质时损失1/1D8 SAN。刚转化不久，模组明确其目前尚不受日光伤害。APP、EDU和SAN为外部怪物条目未提供的运行时补位值0。'
                END
            )
        )
    FROM (
        VALUES
            (
                9,
                '奥古斯特',
                TRUE,
                '腐烂枯朽、动作僵硬的南北战争逃兵活尸。',
                '信奉格拉基并以永生诱骗受害者，白天避免离开阴影。',
                '特纳小屋地下室、黑湖与挖掘现场。',
                '白天通常躺在特纳小屋的蓝色石棺中；被发现时会先尝试说服调查员加入，失败后才动手制服。'
            ),
            (
                10,
                '雅各布',
                TRUE,
                '腐烂枯朽、动作僵硬的南北战争逃兵活尸。',
                '信奉格拉基并以永生诱骗受害者，白天避免离开阴影。',
                '特纳小屋地下室、黑湖与挖掘现场。',
                '白天通常与奥古斯特一起躺在蓝色石棺中；会把俘虏塞入空棺，等待入夜后送往湖畔。'
            ),
            (
                11,
                '文森特',
                TRUE,
                '腐烂枯朽、动作僵硬的南北战争逃兵活尸。',
                '冷静服从特纳，善于在阴影中伏击并控制俘虏。',
                '挖掘现场第四棚屋、黑湖与特纳小屋。',
                '第三天白昼可能躲在挖掘现场第四棚屋隔壁，听到卡尔的圈套引发打斗便前来协助。'
            ),
            (
                12,
                '路易斯',
                TRUE,
                '腐烂枯朽、动作僵硬的南北战争逃兵活尸。',
                '谨慎、狡猾，以侦察和制造混乱为先，不会无意义地战死。',
                '森林追踪路线、挖掘现场与黑湖。',
                '第二夜监视调查员并准备回营报告。被逼入绝境时会借一次有分量的攻击装死，待无人注意后逃走；若未被处理，第三天在第四棚屋附近协助抓捕。'
            ),
            (
                13,
                '卡尔·怀特',
                FALSE,
                '胸口带有脊刺伤口但气色仍接近常人的新转化仆从。',
                '表面友好、有条理，擅长用半真解释降低来客戒心，再把人引入圈套。',
                '水晶挖掘现场。',
                '勘探队领班。以“发现铂金迹象”掩盖污染物清除任务，邀请调查员参观后把他们带进关押俘虏的第四棚屋。'
            ),
            (
                14,
                '詹姆斯·斯坦顿',
                FALSE,
                '外套遮住胸前可怕脊刺伤口，外观仍足以冒充普通工程师。',
                '冷酷、敌视但并非无脑僵尸，会隐瞒目的并观察潜在干扰者。',
                '勘探卡车道路、水晶挖掘现场与黑湖。',
                '驾驶装有数箱炸药的卡车。若湖畔画家尖叫并且团体幸运失败，他会与迪恩前去查看。'
            ),
            (
                15,
                '迪恩·沃尔特斯',
                FALSE,
                '刚被转化，脊刺伤口可被衣物遮掩，乍看仍像普通勘探队员。',
                '服从卡尔与格拉基，专注挖掘水晶和控制入侵者。',
                '水晶挖掘现场与黑湖。',
                '在坑洞和工具棚之间搬运绳索、钻具；若湖畔出现动静，会与詹姆斯前去查看。'
            ),
            (
                16,
                '理查德·吉布森',
                FALSE,
                '气色尚可、能够自然交谈的新转化仆从。',
                '礼貌而坚定，先以施工安全为由阻挡来客，冲突升级时才召集同伴。',
                '水晶挖掘现场入口。',
                '守卫五杆门。交涉成功才带调查员去见卡尔；失败会赶走调查员，孤注一掷失败则可能引来其他仆从。'
            )
    ) AS servant(
        sort_order,
        name,
        old_servant,
        appearance,
        traits,
        locations,
        notes
    );

    INSERT INTO coc_module_character (
        module_id,
        sort_order,
        card_data
    ) VALUES
        (
            v_module_id,
            17,
            $json$
            {
              "character": {
                "name": "格拉基",
                "occupation": "旧日支配者、湖中居物",
                "era": "1925年",
                "str": 200, "con": 300, "siz": 450, "dex": 50,
                "app": 0, "intValue": 150, "pow": 140, "edu": 0,
                "damageBonus": "+7D6", "build": 8, "mov": 6,
                "hpCurrent": 75, "hpMax": 75,
                "sanCurrent": 0, "sanMax": 0,
                "mpCurrent": 28, "mpMax": 28,
                "armor": 40,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"格斗","category":"格斗","specialization":"","value":100,"isCustom":true}
              ],
              "weapons": [
                {"name":"脊刺","skillName":"格斗","damage":"3D10","range":"近战或投射","attacksPerRound":"1","isBroken":false,"notes":"可刺穿敌人或把脊刺射向目标。用于转化时，下一轮会通过脊刺向受害者注入液体。"}
              ],
              "profile": {
                "appearance":"巨大的卵形躯体遍布金属般的细锐脊刺；圆端是松软面孔、厚圆嘴唇和三根连着黄色眼睛的肉柄，腹部生有许多白色锥体。",
                "ideology":"借梦唤吸引受害者，以脊刺创造不死奴仆，并命令奴仆寻找水晶牢笼碎片以削弱囚禁自己的力量。",
                "meaningfulLocations":"黑湖及其附近的水晶碎片。",
                "traits":"虚弱但极其古老，能与仆从共享记忆；多数教徒是不死生物。",
                "notes":"每轮攻击1次。外壳提供40点护甲；每根脊刺单独具有4点护甲和6 HP。目睹格拉基损失1D3/1D20 SAN。梦唤基础成功率为格拉基MP 28减去目标MP；目标与巢穴每相隔800米，计算时视为目标额外增加1 MP，每晚可尝试一次。脊刺通常会杀死人类，下一轮注液后令其成为格拉基之仆；若受害者在注液前挣脱，则依伤势死亡或极罕见地幸存。格拉基知晓大多数法术，并会把许多法术传授给奴仆。APP、EDU和SAN为外部怪物条目未提供的运行时补位值0。"
              }
            }
            $json$::jsonb
        ),
        (
            v_module_id,
            18,
            $json$
            {
              "character": {
                "name": "美洲黑熊（可选遭遇）",
                "occupation": "北美野兽",
                "era": "1925年",
                "str": 100, "con": 65, "siz": 100, "dex": 50,
                "app": 0, "intValue": 0, "pow": 50, "edu": 0,
                "damageBonus": "+2D6", "build": 3, "mov": 12,
                "hpCurrent": 16, "hpMax": 16,
                "sanCurrent": 0, "sanMax": 0,
                "mpCurrent": 10, "mpMax": 10,
                "armor": 3,
                "majorWound": false, "unconscious": false,
                "dying": false, "dead": false, "temporaryInsanity": false
              },
              "skills": [
                {"displayName":"斗殴","category":"格斗","specialization":"","baseValue":25,"value":40,"isCustom":false},
                {"displayName":"闪避","category":"战斗","specialization":"","baseValue":25,"value":25,"isCustom":false},
                {"displayName":"攀爬","category":"行动","specialization":"","baseValue":20,"value":30,"isCustom":false},
                {"displayName":"聆听","category":"感知","specialization":"","baseValue":20,"value":75,"isCustom":false},
                {"displayName":"嗅探猎物","category":"非常规","specialization":"","value":70,"isCustom":true}
              ],
              "weapons": [
                {"name":"掌爪与牙齿","skillName":"斗殴","damage":"1D6+DB","attacksPerRound":"2","isBroken":false}
              ],
              "profile": {
                "appearance":"美国东部最常见的熊类，体格硕大，生有强壮掌爪与牙齿。",
                "traits":"受到调查员惊扰时发动短暂攻击，造成一些伤害后会逃离，不作为主线敌人死战。",
                "meaningfulLocations":"森林追踪路线中的孤注一掷可选遭遇。",
                "notes":"每轮攻击2次。皮毛和软骨提供3点护甲。第十四章未提供INT、APP、EDU或SAN，运行时补位为0；这些值不代表原书设定。"
              }
            }
            $json$::jsonb
        );
END
$module$;
