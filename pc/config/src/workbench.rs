//! 远程工作台布局（schema 3）：悬浮球 8 个槽位、语音条「快捷发送」和改字行、键鼠条按键区、侧栏「我的按钮」里各放什么，
//! 外加横屏、竖屏各自的默认位置和大小。这里只有「引用」：手机动作（fn:）、基础键（key:）、正式版按键（button:）。
//! 按键做什么还是正式版映射说了算，这里不存任何按键映射。
//! 规则和手机端 WorkbenchLayoutV3.kt 一样，改一边要改另一边。

use std::collections::HashSet;

use serde_json::{json, Map, Value};

use crate::{Config, ModuleKind, Page};

/// 存在配置文件顶层的这个键下（旧版电脑通过 Config.extra 原样保留）
pub const KEY: &str = "screenWorkbenchV3";
/// 一次性补丁做过哪些（字符串数组，也在配置顶层，手机不碰）。做过的再也不做：用户后来拿掉了不会再加回来
pub const UPGRADES_KEY: &str = "screenWorkbenchV3Upgrades";
/// 布局坏到修不了、要按默认重新生成之前，把原来那份放在这里（配置顶层），万一要找回来
pub const PREVIOUS_KEY: &str = "screenWorkbenchV3Previous";
/// 工作台默认热键放在这一页（正式版页面，电脑上能照常编辑它们的映射）
pub const SHORTCUT_PAGE: &str = "remote-shortcuts";
pub const SHORTCUT_PAGE_NAME: &str = "远程快捷键";
pub const BALL_SLOTS: usize = 8;
pub const MAX_ROW: usize = 24;
/// 永远在悬浮球里：能换位置，不能拿掉（拿掉了就再也打不开语音条、键鼠条，也退不出精密鼠标）
pub const FIXED_FUNCTIONS: [&str; 3] = ["voice", "keymouse", "pointer"];
/// 能放进任何槽位的手机动作（整个布局里只放一处）。sendFile「发图片」= 正式版「⋯ → 发文件到电脑」
pub const PLACEABLE_FUNCTIONS: [&str; 5] = ["taskSwitch", "captureWechat", "captureQq", "captureSystem", "sendFile"];
/// 基础键：同一排不重复，不同排可以各放一个（语音条改字和键鼠条都要用退格、方向）
pub const BASIC_KEYS: [&str; 11] = [
    "backspace", "delete", "space", "enter", "esc", "tab", "left", "right", "up", "down", "capslock",
];
/// 手机动作在电脑上显示的名字（和手机 WorkbenchCatalog 一样）
pub const FUNCTION_LABELS: [(&str, &str); 8] = [
    ("voice", "语音"),
    ("keymouse", "键鼠"),
    ("pointer", "精密鼠标"),
    ("taskSwitch", "切换应用"),
    ("captureWechat", "微信截图"),
    ("captureQq", "QQ截图"),
    ("captureSystem", "系统截图"),
    ("sendFile", "发图片"),
];
/// 基础键在电脑上显示的名字（和手机 WorkbenchCatalog 一样）
pub const KEY_LABELS: [(&str, &str); 11] = [
    ("backspace", "退格"),
    ("delete", "删除"),
    ("space", "空格"),
    ("enter", "回车"),
    ("esc", "Esc"),
    ("tab", "Tab"),
    ("left", "左"),
    ("right", "右"),
    ("up", "上"),
    ("down", "下"),
    ("capslock", "大小写"),
];
/// 语音条上的「快捷发送」：点一下把常用文本打进电脑。只能放正式版按键
pub const QUICK_TEXT: &str = "quickText";
/// 悬浮球以外的几排（顺序和手机 toJson 一样）
const ROWS: [&str; 4] = ["voiceRow", QUICK_TEXT, "keymouse", "rail"];
const TOOLS: [&str; 2] = ["voice", "keymouse"];
/// 「发图片」是后加的：已有的布局靠这个一次性补丁放进悬浮球
const SEND_FILE_UPGRADE: &str = "sendFile";
/// 旧版（schema 2）工作台只在第一次生成新布局时导入一次，记下来（见 first_migration）。
/// 以前「恢复默认」、布局坏了重建时也读它，而按键 id 会被复用，旧版里指着的按键早就换了人（审查 #3）
const V2_IMPORT_UPGRADE: &str = "v2Import";

/// 默认布局要用到的正式版热键：（名字，组合键，按之前要不要确认）
const DEFAULT_HOTKEYS: [(&str, &[&str], bool); 6] = [
    ("Alt+F4", &["Alt", "F4"], true),
    ("Alt+E", &["Alt", "E"], false),
    ("全选", &["Ctrl", "A"], false),
    ("复制", &["Ctrl", "C"], false),
    ("粘贴", &["Ctrl", "V"], false),
    ("撤销", &["Ctrl", "Z"], false),
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Ref {
    Function(String),
    Key(String),
    Button(String),
}

impl Ref {
    pub fn parse(token: &str, button_ids: &HashSet<String>) -> Result<Ref, String> {
        let (kind, value) = token.split_once(':').ok_or_else(|| format!("不认识的按钮：{token}"))?;
        match kind {
            "fn" if FIXED_FUNCTIONS.contains(&value) || PLACEABLE_FUNCTIONS.contains(&value) => {
                Ok(Ref::Function(value.into()))
            }
            "key" if BASIC_KEYS.contains(&value) => Ok(Ref::Key(value.into())),
            "button" if button_ids.contains(value) => Ok(Ref::Button(value.into())),
            "button" => Err(format!("引用的电脑按键不存在：{value}")),
            _ => Err(format!("不认识的按钮：{token}")),
        }
    }

    pub fn token(&self) -> String {
        match self {
            Ref::Function(v) => format!("fn:{v}"),
            Ref::Key(v) => format!("key:{v}"),
            Ref::Button(v) => format!("button:{v}"),
        }
    }
}

/// 工作台专用页：放工作台热键、快捷发送的那一页（SHORTCUT_PAGE）。用户会把它当成多出来的一页删掉，
/// 悬浮球、键鼠条上的热键就跟着没了，所以电脑「布局」页、手机普通按键页都不显示它，「添加按键」也不往里放。
/// 正式版 0.7.22 看到的还是一页普通页（改不了，也不影响）
pub fn is_storage_page(page: &Page) -> bool {
    page.id == SHORTCUT_PAGE
}

/// 删掉专用页上、工作台布局里哪儿都没用到的按键（各方案里它们的映射一起删）。删了返回 true。
/// 只在工作台布局保存之后调（后台存手机发来的布局、恢复默认，电脑「远程工作台」标签每改一次）：
/// 手机「调整布局」里「新建热键」是先建按键、点「完成」才放进布局，平时就调会把刚建的删掉。
/// 没有布局、读不出槽位时一个都不删（不知道哪些在用）
pub fn collect_unused_storage_buttons(config: &mut Config) -> bool {
    let Some(used) = config.extra.get(KEY).and_then(referenced_buttons) else { return false };
    let mut removed: Vec<String> = Vec::new();
    for page in config.layout.pages.iter_mut().filter(|p| is_storage_page(p)) {
        page.modules.retain(|m| {
            // 只删按键：专用页上别的模块（正式版里加的触控板之类）工作台本来就不用，留着
            let unused = m.kind == ModuleKind::Button && !used.contains(&m.id);
            if unused {
                removed.push(m.id.clone());
            }
            !unused
        });
    }
    for profile in &mut config.profiles {
        for id in &removed {
            profile.buttons.remove(id);
        }
    }
    !removed.is_empty()
}

/// 布局里所有 button: 引用（每一排都看，包括这个版本还不认识的排，免得删掉更新的版本放在新排里的按键）。
/// 读不出槽位返回 None
fn referenced_buttons(layout: &Value) -> Option<HashSet<String>> {
    let slots = layout.get("slots")?.as_object()?;
    Some(slots.values().filter_map(Value::as_array).flatten()
        .filter_map(|v| v.as_str()?.strip_prefix("button:"))
        .map(String::from)
        .collect())
}

/// 所有页面上的正式版按键 id
pub fn official_button_ids(config: &Config) -> HashSet<String> {
    config.layout.pages.iter().flat_map(|p| &p.modules)
        .filter(|m| m.kind == ModuleKind::Button)
        .map(|m| m.id.clone())
        .collect()
}

/// 引用在电脑上显示的名字：手机动作、基础键查上面的表，正式版按键用按键上的字
pub fn label(config: &Config, token: &str) -> String {
    let table = |list: &[(&str, &str)], value: &str| list.iter().find(|(id, _)| *id == value).map(|(_, name)| name.to_string());
    let found = match token.split_once(':') {
        Some(("fn", value)) => table(&FUNCTION_LABELS, value),
        Some(("key", value)) => table(&KEY_LABELS, value),
        Some(("button", value)) => config.find_module(value).map(|m| m.title()),
        _ => None,
    };
    found.unwrap_or_else(|| token.to_string())
}

/// 校验并整理成标准格式（多余的字段丢掉）。不合格返回原因，调用方不写配置。
pub fn normalize(input: &Value, button_ids: &HashSet<String>) -> Result<Value, String> {
    let root = input.as_object().ok_or("工作台布局必须是对象")?;
    if root.get("schemaVersion").and_then(Value::as_u64) != Some(3) {
        return Err("工作台布局版本不对".into());
    }
    let slots = root.get("slots").and_then(Value::as_object).ok_or("缺少槽位")?;
    // 动作和正式版按键：整个布局里只出现一次
    let mut unique = HashSet::new();
    let ball_raw = slots.get("ball").and_then(Value::as_array).ok_or("缺少悬浮球槽位")?;
    if ball_raw.len() != BALL_SLOTS {
        return Err("悬浮球必须是 8 个槽位".into());
    }
    let mut ball_keys = HashSet::new();
    let mut ball = Vec::with_capacity(BALL_SLOTS);
    for item in ball_raw {
        if item.is_null() {
            ball.push(Value::Null);
            continue;
        }
        let r = Ref::parse(item.as_str().ok_or("槽位格式不对")?, button_ids)?;
        check_unique(&r, &mut unique, &mut ball_keys)?;
        ball.push(Value::String(r.token()));
    }
    for f in FIXED_FUNCTIONS {
        let token = format!("fn:{f}");
        if !ball.iter().any(|v| v.as_str() == Some(token.as_str())) {
            return Err("语音、键鼠、精密鼠标必须留在悬浮球里".into());
        }
    }
    let mut out_slots = Map::new();
    out_slots.insert("ball".into(), Value::Array(ball));
    let empty = Vec::new();
    for name in ROWS {
        let list = match slots.get(name) {
            // 快捷发送是后加的一排：旧的布局没有，当成空的（输出时总是带上）
            None | Some(Value::Null) if name == QUICK_TEXT => &empty,
            other => other.and_then(Value::as_array).ok_or("缺少按键排")?,
        };
        if list.len() > MAX_ROW {
            return Err("一排最多 24 个按钮".into());
        }
        let mut row_keys = HashSet::new();
        let mut out = Vec::with_capacity(list.len());
        for item in list {
            let r = Ref::parse(item.as_str().ok_or("按钮格式不对")?, button_ids)?;
            match &r {
                Ref::Function(f) if FIXED_FUNCTIONS.contains(&f.as_str()) => {
                    return Err("这个按钮只能放在悬浮球里".into());
                }
                Ref::Button(_) => {}
                _ if name == QUICK_TEXT => return Err("快捷发送里只能放电脑上的按键（常用文本）".into()),
                _ => {}
            }
            check_unique(&r, &mut unique, &mut row_keys)?;
            out.push(Value::String(r.token()));
        }
        out_slots.insert(name.into(), Value::Array(out));
    }
    let geometry = root.get("geometry").and_then(Value::as_object).ok_or("缺少位置数据")?;
    let mut geo = Map::new();
    for orientation in ["portrait", "landscape"] {
        let g = geometry.get(orientation).and_then(Value::as_object).ok_or("缺少方向位置数据")?;
        let mut out = Map::new();
        for tool in TOOLS {
            let t = g.get(tool).and_then(Value::as_object).ok_or("缺少工具位置")?;
            out.insert(tool.into(), tool_geometry(t)?);
        }
        let b = g.get("ball").and_then(Value::as_object).ok_or("缺少悬浮球位置")?;
        out.insert("ball".into(), ball_geometry(b)?);
        geo.insert(orientation.into(), Value::Object(out));
    }
    Ok(json!({"schemaVersion": 3, "slots": out_slots, "geometry": geo}))
}

/// 一条工具（语音条、键鼠条）的位置和大小：左上角在屏幕里，宽度比例 0.2～1，缩放 0.6～1.8
fn tool_geometry(t: &Map<String, Value>) -> Result<Value, String> {
    let (x, y) = (unit(t, "x")?, unit(t, "y")?);
    let (w, k) = (number(t, "w")?, number(t, "k")?);
    if !(0.2..=1.0).contains(&w) || !(0.6..=1.8).contains(&k) {
        return Err("工具大小超出范围".into());
    }
    Ok(json!({"x": x, "y": y, "w": w, "k": k}))
}

fn ball_geometry(b: &Map<String, Value>) -> Result<Value, String> {
    Ok(json!({"x": unit(b, "x")?, "y": unit(b, "y")?}))
}

fn check_unique(r: &Ref, unique: &mut HashSet<String>, row_keys: &mut HashSet<String>) -> Result<(), String> {
    if first_place(r, unique, row_keys) { Ok(()) } else { Err("同一个按钮只能放一处".into()) }
}

/// 「一个按钮只放一处」：动作和正式版按键整个布局里只能一次，基础键同一排里只能一次。第一次见到返回 true（并记下）
fn first_place(r: &Ref, unique: &mut HashSet<String>, row_keys: &mut HashSet<String>) -> bool {
    match r {
        Ref::Key(_) => row_keys.insert(r.token()),
        _ => unique.insert(r.token()),
    }
}

fn number(obj: &Map<String, Value>, key: &str) -> Result<f64, String> {
    obj.get(key).and_then(Value::as_f64).filter(|v| v.is_finite()).ok_or_else(|| format!("位置数据 {key} 无效"))
}

fn unit(obj: &Map<String, Value>, key: &str) -> Result<f64, String> {
    let v = number(obj, key)?;
    if (0.0..=1.0).contains(&v) { Ok(v) } else { Err(format!("位置数据 {key} 超出屏幕")) }
}

/// 布局引用了哪些正式版按键（每个只列一次）
pub fn button_refs(layout: &Value) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    if let Some(slots) = layout.get("slots").and_then(Value::as_object) {
        for name in ["ball", "voiceRow", QUICK_TEXT, "keymouse", "rail"] {
            for item in slots.get(name).and_then(Value::as_array).into_iter().flatten() {
                if let Some(id) = item.as_str().and_then(|t| t.strip_prefix("button:")) {
                    if !out.iter().any(|x| x == id) {
                        out.push(id.to_string());
                    }
                }
            }
        }
    }
    out
}

/// 去掉布局里指向已经没有的正式版按键的引用：悬浮球那格变空，各排里去掉。返回 true = 去掉了。
/// 电脑「远程工作台」标签显示前也这样去掉（删了按键、还没保存时）
pub fn prune_slots(layout: &mut Value, ids: &HashSet<String>) -> bool {
    let Some(slots) = layout.get_mut("slots").and_then(Value::as_object_mut) else { return false };
    let gone = |v: &Value| v.as_str().and_then(|t| t.strip_prefix("button:")).is_some_and(|id| !ids.contains(id));
    let mut changed = false;
    for (name, list) in slots.iter_mut() {
        let Some(list) = list.as_array_mut() else { continue };
        if name == "ball" {
            for item in list.iter_mut() {
                if gone(item) {
                    *item = Value::Null;
                    changed = true;
                }
            }
        } else if ROWS.contains(&name.as_str()) {
            let before = list.len();
            list.retain(|v| !gone(v));
            changed |= list.len() != before;
        }
    }
    changed
}

/// 电脑上删掉了被工作台用着的按键：不拦着，工作台里指向它的引用跟着拿掉（悬浮球那格变空，各排里去掉）。
/// 主界面保存前、后台写配置前调。返回 true = 改了配置
pub fn prune_missing(config: &mut Config) -> bool {
    let ids = official_button_ids(config);
    config.extra.get_mut(KEY).is_some_and(|layout| prune_slots(layout, &ids))
}

/// 默认位置（左上角，占屏幕的比例）：竖屏画面靠上，键鼠条在画面下面、语音条在最下面；
/// 横屏两条工具默认收成小把手（手机本地状态），这里是展开时的位置。
pub fn default_geometry() -> Value {
    json!({
        "portrait": {
            "voice": {"x": 0.02, "y": 0.86, "w": 0.96, "k": 1.0},
            "keymouse": {"x": 0.02, "y": 0.27, "w": 0.96, "k": 1.0},
            "ball": {"x": 0.85, "y": 0.72}
        },
        "landscape": {
            "voice": {"x": 0.10, "y": 0.72, "w": 0.36, "k": 1.0},
            "keymouse": {"x": 0.50, "y": 0.60, "w": 0.42, "k": 1.0},
            "ball": {"x": 0.015, "y": 0.08}
        }
    })
}

/// 保证配置里有一份合格的工作台布局。返回 true = 改了配置（调用方负责写文件）。
/// - 已有且合格：不动（只补一次后加的东西，见 upgrade）；
/// - 有点不合格（引用了已删的按键、认不出的动作、重复、一排太长、位置越界……）：修好，其余保留（见 repair）；
/// - 没有，或者结构坏到修不了：原来那份留在 PREVIOUS_KEY，按默认生成（见 generate）。
///   旧版（schema 2）里用户自己加的按键只在第一次生成时放进侧栏（见 first_migration）
pub fn ensure(config: &mut Config) -> bool {
    let ids = official_button_ids(config);
    if let Some(existing) = config.extra.get(KEY) {
        if normalize(existing, &ids).is_ok() {
            return upgrade(config);
        }
        if let Some(repaired) = repair(existing, &ids) {
            config.extra.insert(KEY.into(), repaired);
            upgrade(config);
            return true;
        }
        let previous = existing.clone();
        config.extra.insert(PREVIOUS_KEY.into(), previous);
    }
    let rail = if first_migration(config) { v2_button_ids(config, &ids) } else { Vec::new() };
    generate(config, rail, Vec::new())
}

/// 「恢复默认」：快捷发送那一排留着（只留还在的按键），别的都按默认重新生成（缺的默认热键补建）。
/// 不看旧版（schema 2）。返回 true = 生成好了（调用方负责写文件；之后清掉没人用的专用页按键）
pub fn reset(config: &mut Config) -> bool {
    let ids = official_button_ids(config);
    let quick: Vec<String> = config.extra.get(KEY)
        .and_then(|layout| layout.get("slots")?.get(QUICK_TEXT)?.as_array().cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(|v| v.as_str()?.strip_prefix("button:"))
        .filter(|id| ids.contains(*id))
        .map(String::from)
        .collect();
    generate(config, Vec::new(), quick)
}

/// 第一次生成新布局：配置里从来没有过 schema 3（没有布局，也没有做过任何一次性补丁）。
/// 只有这时才把旧版（schema 2）里的按键导进来；以后恢复默认、坏了重建都不再看它
fn first_migration(config: &Config) -> bool {
    !config.extra.contains_key(KEY) && !config.extra.contains_key(UPGRADES_KEY)
}

/// 按默认生成布局，缺的默认热键建在专用页上（同组合键的已有按键直接用）。
/// rail：放进侧栏的按键（第一次迁移时旧版里的）；quick：快捷发送里留着的按键（恢复默认时原来那一排）。
/// 已经放在默认位置上的不重复放。返回 true = 写进了配置
fn generate(config: &mut Config, rail: Vec<String>, quick: Vec<String>) -> bool {
    ensure_page(config);
    let mut hotkeys: Vec<Option<String>> = Vec::new();
    for (label, keys, confirm) in DEFAULT_HOTKEYS {
        let keys: Vec<String> = keys.iter().map(|k| k.to_string()).collect();
        let id = find_chord_button(config, &keys)
            .or_else(|| config.create_key_button(SHORTCUT_PAGE, label, &keys).ok());
        if let (Some(id), true) = (&id, confirm) {
            set_confirm(config, id);
        }
        hotkeys.push(id);
    }
    let token = |i: usize| hotkeys[i].as_ref().map(|id| Value::String(format!("button:{id}")));
    let mut ball = vec![json!("fn:voice"), json!("fn:keymouse"), json!("fn:pointer"),
        json!("fn:taskSwitch"), json!("fn:captureWechat")];
    ball.push(token(0).unwrap_or(Value::Null));
    ball.push(token(1).unwrap_or(Value::Null));
    ball.push(json!("fn:sendFile"));
    let voice_row: Vec<Value> = ["backspace", "space", "left", "right", "up", "down", "capslock", "enter"]
        .iter().map(|k| json!(format!("key:{k}"))).collect();
    let mut keymouse: Vec<Value> = ["backspace", "delete", "left", "right", "enter", "esc"]
        .iter().map(|k| json!(format!("key:{k}"))).collect();
    keymouse.extend((2..6).filter_map(token));
    keymouse.push(json!("fn:captureSystem"));
    let ids = official_button_ids(config);
    let mut placed: HashSet<String> = ball.iter().chain(&keymouse).filter_map(Value::as_str).map(String::from).collect();
    let mut place = |list: Vec<String>| -> Vec<Value> {
        list.into_iter()
            .map(|id| format!("button:{id}"))
            .filter(|t| placed.insert(t.clone()))
            .take(MAX_ROW)
            .map(Value::String)
            .collect()
    };
    let quick = place(quick);
    // 旧版工作台里用户自己加的正式版按键：没在默认布局里的，放进侧栏「我的按钮」
    let rail = place(rail);
    let layout = json!({"schemaVersion": 3,
        "slots": {"ball": ball, "voiceRow": voice_row, QUICK_TEXT: quick, "keymouse": keymouse, "rail": rail},
        "geometry": default_geometry()});
    match normalize(&layout, &ids) {
        Ok(valid) => {
            config.extra.insert(KEY.into(), valid);
            // 默认布局里本来就有「发图片」：记成补过了，以后用户拿掉不会再加回来。旧版导没导都不再导
            mark_upgraded(config, SEND_FILE_UPGRADE);
            mark_upgraded(config, V2_IMPORT_UPGRADE);
            true
        }
        Err(_) => false,
    }
}

/// 已有的布局补一次后加的东西，每样只补一次（补过的记在 UPGRADES_KEY）。返回 true = 改了配置。
/// 「发图片」：哪里都没放、悬浮球又有空格，就放进第一个空格（满了不放，用户自己挑地方）
fn upgrade(config: &mut Config) -> bool {
    if upgraded(config, SEND_FILE_UPGRADE) {
        return false;
    }
    let ids = official_button_ids(config);
    let token = "fn:sendFile";
    if let Some(mut slots) = config.extra.get(KEY).and_then(Slots::from_layout) {
        if slots.find(token).is_none() && slots.put(token, Place::Ball, None, None).is_ok() {
            if let Ok(valid) = normalize(&slots.to_layout(), &ids) {
                config.extra.insert(KEY.into(), valid);
            }
        }
    }
    mark_upgraded(config, SEND_FILE_UPGRADE);
    true
}

fn upgraded(config: &Config, name: &str) -> bool {
    config.extra.get(UPGRADES_KEY).and_then(Value::as_array).is_some_and(|done| done.iter().any(|v| v.as_str() == Some(name)))
}

fn mark_upgraded(config: &mut Config, name: &str) {
    if upgraded(config, name) {
        return;
    }
    let mut done = config.extra.get(UPGRADES_KEY).and_then(Value::as_array).cloned().unwrap_or_default();
    done.push(Value::String(name.into()));
    config.extra.insert(UPGRADES_KEY.into(), Value::Array(done));
}

/// 修一份不太合格的布局，像手机读布局时一样宽松（审查 #5：以前只会去掉已删的按键，别的不合格一律整份重建）：
/// - 认不出的（新版本加的 fn:、写错的）、引用了已删按键的、放错地方的（固定动作不在悬浮球、快捷发送里放了动作）去掉；
/// - 重复的留第一个（悬浮球在前，然后按 voiceRow、quickText、keymouse、rail 的顺序）；
/// - 一排超过 24 个的截掉后面的；悬浮球多了截掉、少了补空格；
/// - 语音、键鼠、精密鼠标不在悬浮球里就放回去（第一个空格，没有空格就换掉最后一个不固定的）；
/// - 位置、大小越界的，只把那一个工具（那个方向）换回默认位置。
/// 修完一定合格；结构根本用不了（不是 schema 3、没有槽位、没有悬浮球）返回 None
fn repair(existing: &Value, ids: &HashSet<String>) -> Option<Value> {
    let root = existing.as_object()?;
    if root.get("schemaVersion").and_then(Value::as_u64) != Some(3) {
        return None;
    }
    let slots = root.get("slots")?.as_object()?;
    let ball_raw = slots.get("ball")?.as_array()?;
    let parse = |item: &Value| item.as_str().and_then(|t| Ref::parse(t, ids).ok());
    let mut unique = HashSet::new();
    let mut ball_keys = HashSet::new();
    let mut ball: Vec<Value> = ball_raw.iter().take(BALL_SLOTS)
        .map(|item| match parse(item) {
            Some(r) if first_place(&r, &mut unique, &mut ball_keys) => Value::String(r.token()),
            _ => Value::Null,
        })
        .collect();
    ball.resize(BALL_SLOTS, Value::Null);
    let mut out_slots = Map::new();
    for name in ROWS {
        let mut row_keys = HashSet::new();
        let mut out = Vec::new();
        for r in slots.get(name).and_then(Value::as_array).into_iter().flatten().filter_map(|item| parse(item)) {
            let allowed = match &r {
                Ref::Function(f) if FIXED_FUNCTIONS.contains(&f.as_str()) => false,
                Ref::Button(_) => true,
                _ => name != QUICK_TEXT,
            };
            if allowed && out.len() < MAX_ROW && first_place(&r, &mut unique, &mut row_keys) {
                out.push(Value::String(r.token()));
            }
        }
        out_slots.insert(name.into(), Value::Array(out));
    }
    let fixed = |v: &Value| v.as_str().and_then(|t| t.strip_prefix("fn:")).is_some_and(|f| FIXED_FUNCTIONS.contains(&f));
    for f in FIXED_FUNCTIONS {
        let token = format!("fn:{f}");
        if ball.iter().any(|v| v.as_str() == Some(token.as_str())) {
            continue;
        }
        let slot = ball.iter().position(Value::is_null).or_else(|| ball.iter().rposition(|v| !fixed(v)));
        if let Some(i) = slot {
            ball[i] = Value::String(token);
        }
    }
    out_slots.insert("ball".into(), Value::Array(ball));
    let defaults = default_geometry();
    let given = root.get("geometry").and_then(Value::as_object);
    let mut geo = Map::new();
    for orientation in ["portrait", "landscape"] {
        let g = given.and_then(|g| g.get(orientation)).and_then(Value::as_object);
        let part = |name: &str| g.and_then(|g| g.get(name)).and_then(Value::as_object);
        let mut out = Map::new();
        for tool in TOOLS {
            let value = part(tool).and_then(|t| tool_geometry(t).ok());
            out.insert(tool.into(), value.unwrap_or_else(|| defaults[orientation][tool].clone()));
        }
        let value = part("ball").and_then(|b| ball_geometry(b).ok());
        out.insert("ball".into(), value.unwrap_or_else(|| defaults[orientation]["ball"].clone()));
        geo.insert(orientation.into(), Value::Object(out));
    }
    normalize(&json!({"schemaVersion": 3, "slots": out_slots, "geometry": geo}), ids).ok()
}

/// 手机上「＋ 新建」常用文本时顺手放进「快捷发送」最后（和建按键是同一次写配置）。
/// 布局不合格、这一排满了就不放，返回 false（按键照样建好）
pub fn append_quick_text(config: &mut Config, button_id: &str) -> bool {
    let ids = official_button_ids(config);
    let Some(mut layout) = config.extra.get(KEY).cloned() else { return false };
    prune_slots(&mut layout, &ids);
    let Some(mut slots) = Slots::from_layout(&layout) else { return false };
    if slots.put(&format!("button:{button_id}"), Place::QuickText, None, None).is_err() {
        return false;
    }
    match normalize(&slots.to_layout(), &ids) {
        Ok(valid) => {
            config.extra.insert(KEY.into(), valid);
            true
        }
        Err(_) => false,
    }
}

fn ensure_page(config: &mut Config) {
    if !config.layout.pages.iter().any(|p| p.id == SHORTCUT_PAGE) {
        config.layout.pages.push(Page {
            id: SHORTCUT_PAGE.into(),
            name: SHORTCUT_PAGE_NAME.into(),
            modules: Vec::new(),
        });
    }
}

/// 「远程快捷键」页上只有一步、正好是这组组合键的按键（比如用户自己建的「全选（Ctrl+A）」）
fn find_chord_button(config: &Config, keys: &[String]) -> Option<String> {
    let page = config.layout.pages.iter().find(|p| p.id == SHORTCUT_PAGE)?;
    page.modules.iter().filter(|m| m.kind == ModuleKind::Button).find(|m| {
        let mut active = m.behaviors.click.iter().filter(|s| s.active());
        match (active.next(), active.next()) {
            (Some(step), None) => step.action.chord().is_some_and(|c| {
                c.len() == keys.len() && c.iter().zip(keys).all(|(a, b)| a.eq_ignore_ascii_case(b))
            }),
            _ => false,
        }
    }).map(|m| m.id.clone())
}

/// 这个按键按之前先问一句（手机端看 confirm；旧版电脑原样保留这个字段）
fn set_confirm(config: &mut Config, id: &str) {
    if let Some(m) = config.layout.pages.iter_mut().flat_map(|p| &mut p.modules).find(|m| m.id == id) {
        m.extra.insert("confirm".into(), Value::Bool(true));
    }
}

/// 旧版（schema 2）工作台里放过的正式版按键 id（按出现顺序、去重、只要还存在的）
fn v2_button_ids(config: &Config, ids: &HashSet<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    if let Some(v2) = config.extra.get("screenWorkbenchV2") {
        for direction in ["portrait", "landscape"] {
            for item in v2.get(direction).and_then(Value::as_array).into_iter().flatten() {
                let id = item.get("actionId").and_then(Value::as_str).and_then(|a| a.strip_prefix("button."));
                if let Some(id) = id {
                    if ids.contains(id) && !out.iter().any(|x| x == id) {
                        out.push(id.to_string());
                    }
                }
            }
        }
    }
    out
}

// ───────── 编辑（电脑「布局」页的「远程工作台」标签用） ─────────

/// 工作台里放东西的五处，顺序是电脑上从左到右、从上到下显示的顺序
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Place {
    Ball,
    QuickText,
    VoiceRow,
    Keymouse,
    Rail,
}

impl Place {
    pub const ALL: [Place; 5] = [Place::Ball, Place::QuickText, Place::VoiceRow, Place::Keymouse, Place::Rail];

    /// 在布局 slots 里的键名
    pub fn key(self) -> &'static str {
        match self {
            Place::Ball => "ball",
            Place::QuickText => QUICK_TEXT,
            Place::VoiceRow => "voiceRow",
            Place::Keymouse => "keymouse",
            Place::Rail => "rail",
        }
    }

    /// 菜单里、「在：…」里说的名字
    pub fn name(self) -> &'static str {
        match self {
            Place::Ball => "悬浮球",
            Place::QuickText => "语音条 · 快捷发送",
            Place::VoiceRow => "语音条 · 编辑",
            Place::Keymouse => "键鼠条",
            Place::Rail => "侧栏",
        }
    }

    /// 界面上的编号（Place::ALL 里的位置）
    pub fn from_index(index: i32) -> Option<Place> {
        usize::try_from(index).ok().and_then(|i| Place::ALL.get(i).copied())
    }
}

/// 工作台各处放了什么（编辑用）：悬浮球固定 8 格（None 是空格），各排按顺序、没有空的。
/// 这里只管挪来挪去，规则（固定的不能拿掉、一个按钮只放一处、快捷发送只放按键……）一律交给 normalize：
/// 改完用 to_layout 生成新布局，normalize 过了才算数
#[derive(Clone, Debug, PartialEq)]
pub struct Slots {
    lists: [Vec<Option<String>>; 5],
    /// 位置和大小：原样带着（在手机上调）
    geometry: Value,
}

impl Slots {
    /// 从布局里读出来；结构不对返回 None
    pub fn from_layout(layout: &Value) -> Option<Slots> {
        let slots = layout.get("slots")?.as_object()?;
        let mut lists: [Vec<Option<String>>; 5] = Default::default();
        for place in Place::ALL {
            let items = match slots.get(place.key()) {
                Some(Value::Array(items)) => items,
                None | Some(Value::Null) if place == Place::QuickText => continue,
                _ => return None,
            };
            let list: Vec<Option<String>> = items.iter().map(|v| v.as_str().map(String::from)).collect();
            if place != Place::Ball && list.iter().any(Option::is_none) {
                return None;
            }
            lists[place as usize] = list;
        }
        Some(Slots { lists, geometry: layout.get("geometry").cloned().unwrap_or(Value::Null) })
    }

    /// 生成布局（还没检查，交给 normalize）
    pub fn to_layout(&self) -> Value {
        let mut slots = Map::new();
        for place in Place::ALL {
            let list: Vec<Value> = self.lists[place as usize].iter().map(|t| t.clone().map_or(Value::Null, Value::String)).collect();
            slots.insert(place.key().into(), Value::Array(list));
        }
        json!({"schemaVersion": 3, "slots": slots, "geometry": self.geometry})
    }

    /// 这一处放了什么（悬浮球的空格是 None）
    pub fn list(&self, place: Place) -> &[Option<String>] {
        &self.lists[place as usize]
    }

    /// 动作和正式版按键放在哪（只会有一处）；基础键可以好几处，不找，返回 None
    pub fn find(&self, token: &str) -> Option<(Place, usize)> {
        if token.starts_with("key:") {
            return None;
        }
        Place::ALL.into_iter().find_map(|place| {
            self.lists[place as usize].iter().position(|t| t.as_deref() == Some(token)).map(|i| (place, i))
        })
    }

    /// 同一处里两个换位置（往前挪、往后挪；悬浮球里固定的也能换位置）。下标不对返回 false
    pub fn swap(&mut self, place: Place, a: usize, b: usize) -> bool {
        let list = &mut self.lists[place as usize];
        if a >= list.len() || b >= list.len() || a == b {
            return false;
        }
        list.swap(a, b);
        true
    }

    /// 拿掉：悬浮球那格变空，各排里直接去掉。返回拿掉的
    pub fn take(&mut self, place: Place, index: usize) -> Option<String> {
        let list = &mut self.lists[place as usize];
        if index >= list.len() {
            return None;
        }
        if place == Place::Ball {
            list[index].take()
        } else {
            list.remove(index)
        }
    }

    /// 把 token 放到 place：index 是要换掉的那一个（悬浮球的空格也算），None 是加到这一排最后（悬浮球放进第一个空格）。
    /// from 是它原来在哪（从按钮库挑的：动作、正式版按键用 find 找；基础键只看这一处有没有），和手机上的规则一样：
    /// - 原来在同一处：和要换掉的那个互换位置；加到最后就挪到最后（悬浮球里不动）；
    /// - 原来在别处：搬过来，原来那处空出来；要换掉的那个拿掉（回到按钮库）。
    pub fn put(&mut self, token: &str, place: Place, index: Option<usize>, from: Option<(Place, usize)>) -> Result<(), String> {
        let target = place as usize;
        if index.is_some_and(|i| i >= self.lists[target].len()) {
            return Err("这个位置已经没有了，再试一次".into());
        }
        let from = from.filter(|&(p, i)| self.lists[p as usize].get(i).and_then(|t| t.as_deref()) == Some(token));
        if let Some((from_place, from_index)) = from {
            if from_place == place {
                match index {
                    Some(i) => {
                        self.lists[target].swap(from_index, i);
                    }
                    None if place == Place::Ball => {}
                    None => {
                        let item = self.lists[target].remove(from_index);
                        self.lists[target].push(item);
                    }
                }
                return Ok(());
            }
            self.take(from_place, from_index);
        }
        let item = Some(token.to_string());
        match index {
            Some(i) => self.lists[target][i] = item,
            None if place == Place::Ball => {
                let free = self.lists[target].iter().position(Option::is_none).ok_or("悬浮球没有空位了：先拿掉一个")?;
                self.lists[target][free] = item;
            }
            None => self.lists[target].push(item),
        }
        Ok(())
    }

    /// 从按钮库挑一个放到 place（index 同 put）：它原来在哪自己找
    pub fn pick(&mut self, token: &str, place: Place, index: Option<usize>) -> Result<(), String> {
        let from = if token.starts_with("key:") {
            self.lists[place as usize].iter().position(|t| t.as_deref() == Some(token)).map(|i| (place, i))
        } else {
            self.find(token)
        };
        self.put(token, place, index, from)
    }

    /// 「移到…」：从 place 的第 index 个搬到 target 最后（悬浮球是第一个空格）
    pub fn move_to(&mut self, place: Place, index: usize, target: Place) -> Result<(), String> {
        let token = self.lists[place as usize].get(index).cloned().flatten().ok_or("这个位置已经没有了，再试一次")?;
        self.put(&token, target, None, Some((place, index)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Config;
    use serde_json::json;

    fn ids(list: &[&str]) -> HashSet<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    fn layout() -> Value {
        json!({"schemaVersion": 3,
            "slots": {
                "ball": ["fn:voice", "fn:keymouse", "fn:pointer", "fn:taskSwitch", "button:b1", null, null, null],
                "voiceRow": ["key:backspace", "key:enter"],
                "keymouse": ["key:backspace", "fn:captureSystem", "button:b2"],
                "rail": []
            },
            "geometry": default_geometry()})
    }

    #[test]
    fn accepts_valid_layout_and_keeps_empty_ball_slots() {
        let v = normalize(&layout(), &ids(&["b1", "b2"])).unwrap();
        assert_eq!(v["slots"]["ball"].as_array().unwrap().len(), BALL_SLOTS);
        assert_eq!(v["slots"]["ball"][5], Value::Null);
        assert_eq!(v["slots"]["keymouse"][2], "button:b2");
    }

    #[test]
    fn basic_keys_repeat_across_rows_but_not_within_one() {
        assert!(normalize(&layout(), &ids(&["b1", "b2"])).is_ok());
        let mut v = layout();
        v["slots"]["voiceRow"] = json!(["key:enter", "key:enter"]);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
    }

    #[test]
    fn functions_and_buttons_are_one_place_only() {
        let mut v = layout();
        v["slots"]["rail"] = json!(["button:b1"]);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
        let mut v = layout();
        v["slots"]["rail"] = json!(["fn:taskSwitch"]);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
    }

    #[test]
    fn fixed_functions_must_stay_in_the_ball() {
        let mut v = layout();
        v["slots"]["ball"][2] = Value::Null;
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
        let mut v = layout();
        v["slots"]["ball"][2] = Value::Null;
        v["slots"]["rail"] = json!(["fn:pointer"]);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
    }

    #[test]
    fn rejects_unknown_refs_missing_buttons_and_bad_geometry() {
        assert!(normalize(&layout(), &ids(&["b1"])).is_err());
        let mut v = layout();
        v["slots"]["rail"] = json!(["script:shutdown"]);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
        let mut v = layout();
        v["geometry"]["portrait"]["voice"]["k"] = json!(3.0);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
        let mut v = layout();
        v["geometry"]["landscape"]["ball"]["x"] = json!(1.5);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
        let mut v = layout();
        v["schemaVersion"] = json!(2);
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
        let mut v = layout();
        v["slots"]["ball"].as_array_mut().unwrap().pop();
        assert!(normalize(&v, &ids(&["b1", "b2"])).is_err());
    }

    #[test]
    fn drops_untrusted_extra_fields() {
        let mut v = layout();
        v["execute"] = json!("shutdown");
        v["geometry"]["portrait"]["voice"]["script"] = json!("x");
        let out = normalize(&v, &ids(&["b1", "b2"])).unwrap();
        assert!(out.get("execute").is_none());
        assert!(out["geometry"]["portrait"]["voice"].get("script").is_none());
    }

    #[test]
    fn button_refs_lists_each_official_id_once() {
        assert_eq!(button_refs(&layout()), vec!["b1".to_string(), "b2".to_string()]);
        let mut v = layout();
        v["slots"][QUICK_TEXT] = json!(["button:t1"]);
        assert_eq!(button_refs(&v), vec!["b1".to_string(), "t1".to_string(), "b2".to_string()]);
    }

    /// 快捷发送：旧布局没有这一排也合格，输出总是带上；只能放正式版按键，参与「只放一处」，最多 24 个
    #[test]
    fn quick_text_row_rules() {
        let all = ids(&["b1", "b2", "t1", "t2"]);
        let out = normalize(&layout(), &all).unwrap();
        assert_eq!(out["slots"][QUICK_TEXT], json!([]));
        let mut v = layout();
        v["slots"][QUICK_TEXT] = Value::Null;
        assert_eq!(normalize(&v, &all).unwrap()["slots"][QUICK_TEXT], json!([]));
        let mut v = layout();
        v["slots"][QUICK_TEXT] = json!(["button:t1", "button:t2"]);
        assert_eq!(normalize(&v, &all).unwrap()["slots"][QUICK_TEXT], json!(["button:t1", "button:t2"]));
        for bad in ["fn:sendFile", "key:enter", "fn:voice"] {
            let mut v = layout();
            v["slots"][QUICK_TEXT] = json!([bad]);
            assert!(normalize(&v, &all).is_err(), "{bad}");
        }
        // 已经在悬浮球里的按键不能再放进快捷发送
        let mut v = layout();
        v["slots"][QUICK_TEXT] = json!(["button:b1"]);
        assert!(normalize(&v, &all).is_err());
        let mut v = layout();
        v["slots"][QUICK_TEXT] = json!(["button:t1", "button:t1"]);
        assert!(normalize(&v, &all).is_err());
        let many: Vec<String> = (0..25).map(|i| format!("q{i}")).collect();
        let many_ids: HashSet<String> = many.iter().cloned().chain(["b1".to_string(), "b2".to_string()]).collect();
        let mut v = layout();
        v["slots"][QUICK_TEXT] = json!(many.iter().map(|id| format!("button:{id}")).collect::<Vec<_>>());
        assert!(normalize(&v, &many_ids).is_err());
        v["slots"][QUICK_TEXT].as_array_mut().unwrap().pop();
        assert!(normalize(&v, &many_ids).is_ok());
    }

    #[test]
    fn ensure_creates_defaults_once() {
        let mut c = Config::fresh();
        assert!(ensure(&mut c));
        let v = c.extra.get(KEY).unwrap().clone();
        assert!(normalize(&v, &official_button_ids(&c)).is_ok());
        let page = c.layout.pages.iter().find(|p| p.id == SHORTCUT_PAGE).unwrap();
        assert_eq!(page.name, SHORTCUT_PAGE_NAME);
        assert_eq!(page.modules.len(), 6);
        let f4 = page.modules.iter().find(|m| m.label == "Alt+F4").unwrap();
        assert_eq!(f4.extra.get("confirm"), Some(&Value::Bool(true)));
        assert_eq!(v["slots"]["ball"][5], json!(format!("button:{}", f4.id)));
        // 新配置：悬浮球第 8 格是「发图片」，快捷发送是空的
        assert_eq!(v["slots"]["ball"][7], "fn:sendFile");
        assert_eq!(v["slots"][QUICK_TEXT], json!([]));
        assert_eq!(c.extra.get(UPGRADES_KEY), Some(&json!(["sendFile", "v2Import"])));
        assert!(!ensure(&mut c));
        assert_eq!(c.layout.pages.iter().find(|p| p.id == SHORTCUT_PAGE).unwrap().modules.len(), 6);
    }

    #[test]
    fn ensure_reuses_same_chord_and_keeps_v2_buttons_in_rail() {
        let mut c = Config::fresh();
        ensure_page(&mut c);
        let select_all = c.create_key_button(SHORTCUT_PAGE, "全选（Ctrl+A）", &["Ctrl".into(), "A".into()]).unwrap();
        let save = c.create_key_button(SHORTCUT_PAGE, "保存", &["Ctrl".into(), "S".into()]).unwrap();
        c.extra.insert("screenWorkbenchV2".into(), json!({"schemaVersion": 2,
            "portrait": [{"actionId": format!("button.{select_all}")}, {"actionId": format!("button.{save}")}],
            "landscape": [{"actionId": "voice"}]}));
        assert!(ensure(&mut c));
        let v = c.extra.get(KEY).unwrap();
        assert!(v["slots"]["keymouse"].as_array().unwrap().contains(&json!(format!("button:{select_all}"))));
        assert_eq!(v["slots"]["rail"], json!([format!("button:{save}")]));
        let page = c.layout.pages.iter().find(|p| p.id == SHORTCUT_PAGE).unwrap();
        assert!(page.modules.iter().all(|m| m.label != "全选"));
    }

    #[test]
    fn broken_layout_is_repaired_or_regenerated() {
        let mut c = Config::fresh();
        assert!(ensure(&mut c));
        // 电脑上删掉了一个被引用的按键：保留其余，只去掉那一个
        let f4 = c.layout.pages.iter().flat_map(|p| &p.modules).find(|m| m.label == "Alt+F4").unwrap().id.clone();
        for page in &mut c.layout.pages {
            page.modules.retain(|m| m.id != f4);
        }
        assert!(ensure(&mut c));
        let v = c.extra.get(KEY).unwrap();
        assert_eq!(v["slots"]["ball"][5], Value::Null);
        assert!(normalize(v, &official_button_ids(&c)).is_ok());
        // 结构坏了：原来那份留一份，按默认重新生成
        c.extra.insert(KEY.into(), json!({"schemaVersion": 3}));
        assert!(ensure(&mut c));
        assert!(normalize(c.extra.get(KEY).unwrap(), &official_button_ids(&c)).is_ok());
        assert_eq!(c.extra[PREVIOUS_KEY], json!({"schemaVersion": 3}));
    }

    /// 没有快捷发送那一排的旧布局，删了按键也只修那一处，不会整个重新生成
    #[test]
    fn repair_keeps_old_layout_without_quick_text() {
        let mut c = Config::fresh();
        ensure_page(&mut c);
        let keep = c.create_key_button(SHORTCUT_PAGE, "保存", &["Ctrl".into(), "S".into()]).unwrap();
        let mut old = layout();
        old["slots"]["ball"][4] = json!("button:gone");
        old["slots"]["rail"] = json!([format!("button:{keep}")]);
        old["slots"]["keymouse"] = json!(["key:backspace", "fn:captureSystem"]);
        c.extra.insert(KEY.into(), old);
        c.extra.insert(UPGRADES_KEY.into(), json!(["sendFile"]));
        assert!(ensure(&mut c));
        let v = c.extra.get(KEY).unwrap();
        assert_eq!(v["slots"]["ball"][4], Value::Null);
        assert_eq!(v["slots"]["rail"], json!([format!("button:{keep}")]));
        assert_eq!(v["slots"][QUICK_TEXT], json!([]));
    }

    /// 已有的布局补一次「发图片」：放进悬浮球第一个空格，只补一次；后来拿掉了不再加回来
    #[test]
    fn send_file_is_added_once_and_respects_removal() {
        let mut c = Config::fresh();
        let mut old = layout();
        old["slots"]["ball"][4] = Value::Null;
        old["slots"]["keymouse"] = json!(["key:backspace", "fn:captureSystem"]);
        c.extra.insert(KEY.into(), old);
        assert!(ensure(&mut c));
        assert_eq!(c.extra[KEY]["slots"]["ball"][4], "fn:sendFile");
        assert_eq!(c.extra[UPGRADES_KEY], json!(["sendFile"]));
        assert!(!ensure(&mut c));
        // 用户拿掉了：再启动也不补
        c.extra.get_mut(KEY).unwrap()["slots"]["ball"][4] = Value::Null;
        assert!(!ensure(&mut c));
        assert_eq!(c.extra[KEY]["slots"]["ball"][4], Value::Null);
        // 「恢复默认」还是默认的样子（第 8 格是发图片），记号不重复
        assert!(reset(&mut c));
        assert_eq!(c.extra[KEY]["slots"]["ball"][7], "fn:sendFile");
        assert_eq!(c.extra[UPGRADES_KEY], json!(["sendFile", "v2Import"]));
        assert!(reset(&mut c));
        assert_eq!(c.extra[UPGRADES_KEY], json!(["sendFile", "v2Import"]));
    }

    /// 已经放了「发图片」、或者悬浮球满了：只记上补过，布局不动
    #[test]
    fn send_file_upgrade_skips_placed_or_full_ball() {
        let mut c = Config::fresh();
        let mut placed = layout();
        placed["slots"]["keymouse"] = json!(["key:backspace", "fn:captureSystem"]);
        placed["slots"]["rail"] = json!(["fn:sendFile"]);
        c.extra.insert(KEY.into(), placed);
        c.extra.insert(UPGRADES_KEY.into(), json!(["future"]));
        assert!(ensure(&mut c));
        assert_eq!(c.extra[KEY]["slots"]["rail"], json!(["fn:sendFile"]));
        assert!(!c.extra[KEY]["slots"]["ball"].as_array().unwrap().contains(&json!("fn:sendFile")));
        assert_eq!(c.extra[UPGRADES_KEY], json!(["future", "sendFile"]));
        let mut c = Config::fresh();
        let mut full = layout();
        full["slots"]["ball"] = json!(["fn:voice", "fn:keymouse", "fn:pointer", "fn:taskSwitch",
            "fn:captureWechat", "fn:captureQq", "key:enter", "key:esc"]);
        full["slots"]["keymouse"] = json!(["key:backspace", "fn:captureSystem"]);
        c.extra.insert(KEY.into(), full.clone());
        assert!(ensure(&mut c));
        assert_eq!(c.extra[KEY]["slots"]["ball"], full["slots"]["ball"]);
        assert_eq!(c.extra[UPGRADES_KEY], json!(["sendFile"]));
    }

    /// 删掉的按键：悬浮球那格变空，各排里去掉；没有工作台、没有可去的时候不算改了
    #[test]
    fn prune_missing_drops_deleted_buttons_only() {
        let mut c = Config::fresh();
        assert!(!prune_missing(&mut c));
        assert!(ensure(&mut c));
        assert!(!prune_missing(&mut c));
        let shortcut = c.layout.pages.iter().find(|p| p.id == SHORTCUT_PAGE).unwrap();
        let f4 = shortcut.modules.iter().find(|m| m.label == "Alt+F4").unwrap().id.clone();
        let copy = shortcut.modules.iter().find(|m| m.label == "复制").unwrap().id.clone();
        let text = c.create_text_button(SHORTCUT_PAGE, "收到", "收到", false).unwrap();
        assert!(append_quick_text(&mut c, &text));
        for page in &mut c.layout.pages {
            page.modules.retain(|m| m.id != f4 && m.id != copy && m.id != text);
        }
        assert!(prune_missing(&mut c));
        let v = c.extra.get(KEY).unwrap();
        assert_eq!(v["slots"]["ball"][5], Value::Null);
        assert!(!v["slots"]["keymouse"].as_array().unwrap().contains(&json!(format!("button:{copy}"))));
        assert_eq!(v["slots"][QUICK_TEXT], json!([]));
        assert!(normalize(v, &official_button_ids(&c)).is_ok());
        assert!(!prune_missing(&mut c));
    }

    #[test]
    fn append_quick_text_respects_full_row_and_bad_layout() {
        let mut c = Config::fresh();
        let first = c.create_key_button("main", "x", &["Ctrl".into(), "A".into()]);
        assert!(first.is_err()); // 主页满了，下面都建在远程快捷键页
        assert!(ensure(&mut c));
        let mut made = Vec::new();
        for i in 0..MAX_ROW {
            let id = c.create_text_button(SHORTCUT_PAGE, &format!("文本{i}"), "好的", false).unwrap();
            assert!(append_quick_text(&mut c, &id));
            made.push(id);
        }
        let extra = c.create_text_button(SHORTCUT_PAGE, "多出来的", "好的", false).unwrap();
        assert!(!append_quick_text(&mut c, &extra));
        assert_eq!(c.extra[KEY]["slots"][QUICK_TEXT].as_array().unwrap().len(), MAX_ROW);
        assert_eq!(c.extra[KEY]["slots"][QUICK_TEXT][0], json!(format!("button:{}", made[0])));
        c.extra.insert(KEY.into(), json!({"schemaVersion": 3}));
        assert!(!append_quick_text(&mut c, &extra));
        c.extra.remove(KEY);
        assert!(!append_quick_text(&mut c, &extra));
    }

    #[test]
    fn every_function_and_key_has_a_label() {
        let c = Config::fresh();
        for f in FIXED_FUNCTIONS.iter().chain(&PLACEABLE_FUNCTIONS) {
            assert_ne!(label(&c, &format!("fn:{f}")), format!("fn:{f}"));
        }
        for k in BASIC_KEYS {
            assert_ne!(label(&c, &format!("key:{k}")), format!("key:{k}"));
        }
        assert_eq!(label(&c, "fn:sendFile"), "发图片");
        assert_eq!(label(&c, "button:esc"), "Esc");
        assert_eq!(label(&c, "button:nope"), "button:nope");
    }

    fn slots() -> Slots {
        Slots::from_layout(&normalize(&layout(), &ids(&["b1", "b2", "t1"])).unwrap()).unwrap()
    }

    fn check(s: &Slots) -> Result<Value, String> {
        normalize(&s.to_layout(), &ids(&["b1", "b2", "t1"]))
    }

    /// 往前挪、往后挪：同一处里和旁边那个换位置，固定的也能挪
    #[test]
    fn slots_swap_and_take() {
        let mut s = slots();
        assert!(s.swap(Place::Ball, 2, 3));
        assert_eq!(s.list(Place::Ball)[2].as_deref(), Some("fn:taskSwitch"));
        assert!(!s.swap(Place::Ball, 7, 8));
        assert!(s.swap(Place::Keymouse, 0, 1));
        assert_eq!(s.list(Place::Keymouse)[0].as_deref(), Some("fn:captureSystem"));
        assert!(check(&s).is_ok());
        assert_eq!(s.take(Place::Ball, 4).as_deref(), Some("button:b1"));
        assert_eq!(s.list(Place::Ball)[4], None);
        assert_eq!(s.take(Place::VoiceRow, 0).as_deref(), Some("key:backspace"));
        assert_eq!(s.list(Place::VoiceRow).len(), 1);
        assert!(check(&s).is_ok());
        // 固定的拿掉了：normalize 不让
        let mut s = slots();
        s.take(Place::Ball, 0);
        assert!(check(&s).is_err());
    }

    /// 从按钮库挑：动作、正式版按键是搬过来；在悬浮球里就和要换掉的那格互换
    #[test]
    fn slots_pick_moves_unique_refs() {
        let mut s = slots();
        s.pick("button:b1", Place::Rail, None).unwrap();
        assert_eq!(s.list(Place::Ball)[4], None);
        assert_eq!(s.list(Place::Rail), &[Some("button:b1".to_string())]);
        assert!(check(&s).is_ok());
        // 换掉悬浮球的一格：原来在悬浮球里的互换
        let mut s = slots();
        s.pick("button:b1", Place::Ball, Some(3)).unwrap();
        assert_eq!(s.list(Place::Ball)[3].as_deref(), Some("button:b1"));
        assert_eq!(s.list(Place::Ball)[4].as_deref(), Some("fn:taskSwitch"));
        // 从别处搬来换掉一格：被换掉的回到按钮库
        let mut s = slots();
        s.pick("fn:captureSystem", Place::Ball, Some(3)).unwrap();
        assert_eq!(s.list(Place::Ball)[3].as_deref(), Some("fn:captureSystem"));
        assert_eq!(s.find("fn:taskSwitch"), None);
        assert_eq!(s.list(Place::Keymouse).len(), 2);
        assert!(check(&s).is_ok());
        // 加进悬浮球：放进第一个空格；满了报原因
        let mut s = slots();
        s.pick("fn:sendFile", Place::Ball, None).unwrap();
        assert_eq!(s.list(Place::Ball)[5].as_deref(), Some("fn:sendFile"));
        s.pick("fn:captureQq", Place::Ball, None).unwrap();
        s.pick("key:esc", Place::Ball, None).unwrap();
        assert!(s.pick("key:tab", Place::Ball, None).is_err());
        // 基础键不搬：别的排还留着，同一排里是挪位置
        let mut s = slots();
        s.pick("key:backspace", Place::Rail, None).unwrap();
        assert_eq!(s.list(Place::VoiceRow)[0].as_deref(), Some("key:backspace"));
        s.pick("key:backspace", Place::VoiceRow, None).unwrap();
        assert_eq!(s.list(Place::VoiceRow), &[Some("key:enter".to_string()), Some("key:backspace".to_string())]);
        assert!(check(&s).is_ok());
        // 快捷发送只放按键：放了动作 normalize 不让
        let mut s = slots();
        s.pick("fn:sendFile", Place::QuickText, None).unwrap();
        assert!(check(&s).is_err());
        let mut s = slots();
        s.pick("button:t1", Place::QuickText, None).unwrap();
        assert_eq!(check(&s).unwrap()["slots"][QUICK_TEXT], json!(["button:t1"]));
        // 位置不对：不动
        let mut s = slots();
        let before = s.clone();
        assert!(s.pick("fn:sendFile", Place::Rail, Some(3)).is_err());
        assert_eq!(s, before);
    }

    /// 「移到…」：基础键也是搬（原来那排去掉）；悬浮球里的搬走那格变空
    #[test]
    fn slots_move_to_other_place() {
        let mut s = slots();
        s.move_to(Place::VoiceRow, 1, Place::Rail).unwrap();
        assert_eq!(s.list(Place::VoiceRow), &[Some("key:backspace".to_string())]);
        assert_eq!(s.list(Place::Rail), &[Some("key:enter".to_string())]);
        s.move_to(Place::Ball, 4, Place::QuickText).unwrap();
        assert_eq!(s.list(Place::Ball)[4], None);
        assert_eq!(s.list(Place::QuickText), &[Some("button:b1".to_string())]);
        s.move_to(Place::Keymouse, 2, Place::Ball).unwrap();
        assert_eq!(s.list(Place::Ball)[4].as_deref(), Some("button:b2"));
        assert!(check(&s).is_ok());
        assert!(s.move_to(Place::Rail, 5, Place::Ball).is_err());
        // 固定的搬不出悬浮球
        let mut s = slots();
        s.move_to(Place::Ball, 0, Place::Rail).unwrap();
        assert!(check(&s).is_err());
    }

    /// 读旧布局（没有快捷发送）再写回去：多出空的快捷发送，别的不变
    #[test]
    fn slots_round_trip() {
        let v = layout();
        let s = Slots::from_layout(&v).unwrap();
        let out = s.to_layout();
        assert_eq!(out["slots"][QUICK_TEXT], json!([]));
        assert_eq!(out["slots"]["ball"], v["slots"]["ball"]);
        assert_eq!(out["geometry"], v["geometry"]);
        assert!(Slots::from_layout(&json!({"schemaVersion": 3})).is_none());
        let mut bad = layout();
        bad["slots"]["rail"] = json!([null]);
        assert!(Slots::from_layout(&bad).is_none());
    }

    fn shortcut_ids(c: &Config) -> Vec<String> {
        c.layout.pages.iter().find(|p| p.id == SHORTCUT_PAGE).map(|p| p.modules.iter().map(|m| m.id.clone()).collect()).unwrap_or_default()
    }

    /// K1：专用页发给手机时带 "workbench": true（别的页不带）；「添加按键」的空位不找专用页，指定建在专用页上的照样能建
    #[test]
    fn storage_page_is_marked_and_skipped_by_free_spot() {
        let mut c = Config::fresh();
        assert!(ensure(&mut c));
        let index = c.layout.pages.iter().position(|p| p.id == SHORTCUT_PAGE).unwrap();
        assert!(is_storage_page(&c.layout.pages[index]));
        assert!(!is_storage_page(&c.layout.pages[0]));
        let payload = c.phone_payload();
        for page in payload["layout"]["pages"].as_array().unwrap() {
            if page["id"] == SHORTCUT_PAGE {
                assert_eq!(page["workbench"], true);
            } else {
                assert!(page.get("workbench").is_none(), "{page}");
            }
        }
        // 专用页上还有空位，但 free_spot 不给
        assert_eq!(c.free_spot(index, 1.0, 1.0), None);
        assert!(c.free_spot_in(index, 1.0, 1.0).is_some());
        c.layout.pages.push(Page { id: "p2".into(), name: "第 2 页".into(), modules: vec![] });
        assert_eq!(c.free_spot(c.layout.pages.len() - 1, 1.0, 1.0), Some((0.0, 0.0)));
        let id = c.create_text_button(SHORTCUT_PAGE, "收到", "收到", false).unwrap();
        assert!(shortcut_ids(&c).contains(&id));
    }

    /// K2：专用页上、布局里哪儿都没用到的按键删掉（方案里的映射一起删）；用到的、别的页上的、不是按键的都留着
    #[test]
    fn unused_storage_buttons_are_collected() {
        let mut c = Config::fresh();
        // 没有布局：不知道哪些在用，一个都不删
        ensure_page(&mut c);
        let lonely = c.create_text_button(SHORTCUT_PAGE, "孤单", "孤单", false).unwrap();
        assert!(!collect_unused_storage_buttons(&mut c));
        assert!(c.find_module(&lonely).is_some());
        assert!(ensure(&mut c));
        let placed = c.create_text_button(SHORTCUT_PAGE, "放了", "放了", false).unwrap();
        assert!(append_quick_text(&mut c, &placed));
        let loose = c.create_key_button(SHORTCUT_PAGE, "保存", &["Ctrl".into(), "S".into()]).unwrap();
        // 专用页上的触控板（正式版里加的）：不是按键，不删
        c.layout.pages.iter_mut().find(|p| p.id == SHORTCUT_PAGE).unwrap().modules.push(crate::Module {
            id: "pad-x".into(), kind: ModuleKind::Touchpad, label: "触控板".into(), x: 0.0, y: 7.0, w: 1.0, h: 1.0, ..Default::default()
        });
        // 别的页上的按键：也不删
        c.layout.pages.push(Page { id: "p2".into(), name: "第 2 页".into(), modules: vec![] });
        let elsewhere = c.create_text_button("p2", "别处", "别处", false).unwrap();
        let other = c.add_profile("工作", Some(&c.active_profile_id.clone()));
        let before = shortcut_ids(&c).len();
        assert!(collect_unused_storage_buttons(&mut c));
        for gone in [&lonely, &loose] {
            assert!(c.find_module(gone).is_none(), "{gone}");
            assert!(c.profiles.iter().all(|p| !p.buttons.contains_key(gone.as_str())), "{gone}");
        }
        for kept in [&placed, &elsewhere, &"pad-x".to_string()] {
            assert!(c.find_module(kept).is_some(), "{kept}");
        }
        assert_eq!(shortcut_ids(&c).len(), before - 2);
        assert!(c.profiles.iter().find(|p| p.id == other).unwrap().buttons.contains_key(&placed));
        // 默认热键都在布局里：一个都没删
        for label in ["Alt+F4", "Alt+E", "全选", "复制", "粘贴", "撤销"] {
            assert!(c.layout.pages.iter().flat_map(|p| &p.modules).any(|m| m.label == label), "{label}");
        }
        assert!(!collect_unused_storage_buttons(&mut c));
        // 不认识的排里引用的按键（更新的版本加的）也算在用
        let future = c.create_text_button(SHORTCUT_PAGE, "新排", "新排", false).unwrap();
        c.extra.get_mut(KEY).unwrap()["slots"]["future"] = json!([format!("button:{future}")]);
        assert!(!collect_unused_storage_buttons(&mut c));
        assert!(c.find_module(&future).is_some());
    }

    /// 恢复默认：快捷发送那一排留着（去掉已删的、和默认位置重复的），别的按默认；不看旧版（schema 2）
    #[test]
    fn reset_keeps_quick_text_and_ignores_v2() {
        let mut c = Config::fresh();
        assert!(ensure(&mut c));
        let a = c.create_text_button(SHORTCUT_PAGE, "甲", "甲", false).unwrap();
        let b = c.create_text_button(SHORTCUT_PAGE, "乙", "乙", true).unwrap();
        let gone = c.create_text_button(SHORTCUT_PAGE, "丙", "丙", false).unwrap();
        let save = c.create_key_button(SHORTCUT_PAGE, "保存", &["Ctrl".into(), "S".into()]).unwrap();
        let alt_e = c.layout.pages.iter().flat_map(|p| &p.modules).find(|m| m.label == "Alt+E").unwrap().id.clone();
        let mut v = c.extra[KEY].clone();
        // 用户的布局：Alt+E 挪进了快捷发送，悬浮球换了位置，侧栏放了「保存」，位置也调过
        v["slots"]["ball"][6] = Value::Null;
        v["slots"][QUICK_TEXT] = json!([format!("button:{a}"), format!("button:{alt_e}"), format!("button:{gone}"), format!("button:{b}")]);
        v["slots"]["rail"] = json!([format!("button:{save}")]);
        v["slots"]["ball"].as_array_mut().unwrap().swap(0, 3);
        v["geometry"]["portrait"]["ball"] = json!({"x": 0.1, "y": 0.1});
        c.extra.insert(KEY.into(), normalize(&v, &official_button_ids(&c)).unwrap());
        // 旧版（schema 2）里有个按键：恢复默认不再把它放进侧栏
        c.extra.insert("screenWorkbenchV2".into(), json!({"schemaVersion": 2, "portrait": [{"actionId": format!("button.{save}")}]}));
        for page in &mut c.layout.pages {
            page.modules.retain(|m| m.id != gone);
        }
        assert!(reset(&mut c));
        let out = &c.extra[KEY];
        // Alt+E 回到悬浮球默认的位置，快捷发送里就不重复放了
        assert_eq!(out["slots"][QUICK_TEXT], json!([format!("button:{a}"), format!("button:{b}")]));
        assert_eq!(out["slots"]["ball"][6], json!(format!("button:{alt_e}")));
        assert_eq!(out["slots"]["ball"][0], "fn:voice");
        assert_eq!(out["slots"]["rail"], json!([]));
        assert_eq!(out["geometry"], default_geometry());
        assert!(normalize(out, &official_button_ids(&c)).is_ok());
        // 之后清掉没人用的：「保存」不在布局里了
        assert!(collect_unused_storage_buttons(&mut c));
        assert!(c.find_module(&save).is_none());
        assert!(c.find_module(&a).is_some() && c.find_module(&b).is_some());
    }

    /// 旧版（schema 2）只在第一次生成时导入；用过 schema 3 以后布局坏了重建也不再看它（审查 #3）
    #[test]
    fn v2_is_read_only_for_the_first_migration() {
        let mut c = Config::fresh();
        ensure_page(&mut c);
        let save = c.create_key_button(SHORTCUT_PAGE, "保存", &["Ctrl".into(), "S".into()]).unwrap();
        c.extra.insert("screenWorkbenchV2".into(), json!({"schemaVersion": 2,
            "portrait": [{"actionId": format!("button.{save}")}], "landscape": []}));
        let mut first = c.clone();
        assert!(ensure(&mut first));
        assert_eq!(first.extra[KEY]["slots"]["rail"], json!([format!("button:{save}")]));
        // 用过 schema 3（做过补丁）：坏了重建、没了重建都不导
        let mut later = first.clone();
        later.extra.insert(KEY.into(), json!("坏了"));
        assert!(ensure(&mut later));
        assert_eq!(later.extra[KEY]["slots"]["rail"], json!([]));
        assert_eq!(later.extra[PREVIOUS_KEY], json!("坏了"));
        let mut missing = first.clone();
        missing.extra.remove(KEY);
        assert!(ensure(&mut missing));
        assert_eq!(missing.extra[KEY]["slots"]["rail"], json!([]));
        // 只有补丁记号、没有布局（以前的版本做过补丁）：也不导
        let mut marked = c.clone();
        marked.extra.insert(UPGRADES_KEY.into(), json!(["sendFile"]));
        assert!(ensure(&mut marked));
        assert_eq!(marked.extra[KEY]["slots"]["rail"], json!([]));
    }

    /// 有点不合格的布局：修好，别的都留着（审查 #5）
    #[test]
    fn slightly_invalid_layout_is_repaired_not_replaced() {
        let mut c = Config::fresh();
        assert!(ensure(&mut c));
        c.layout.pages.push(Page { id: "p2".into(), name: "第 2 页".into(), modules: vec![] });
        let mine: Vec<String> = (0..30).map(|i| c.create_text_button("p2", &format!("文{i}"), "好", false).unwrap()).collect();
        let ids = official_button_ids(&c);
        let mut v = c.extra[KEY].clone();
        let alt_e = v["slots"]["ball"][6].clone();
        // 认不出的动作（新版本加的）、认不出的写法、不是字符串的：去掉，悬浮球那格变空
        v["slots"]["ball"][3] = json!("fn:teleport");
        v["slots"]["ball"][4] = json!(42);
        // 重复：留第一个（悬浮球里的 Alt+E 留着，侧栏里的去掉）
        v["slots"]["rail"] = json!([alt_e, "script:rm", "fn:voice", "key:esc", "key:esc", format!("button:{}", mine[0])]);
        // 快捷发送放了动作、放了 30 个：动作去掉，只留前 24 个
        let mut quick = vec![json!("fn:sendFile"), json!("key:enter")];
        quick.extend(mine.iter().skip(1).map(|id| json!(format!("button:{id}"))));
        v["slots"][QUICK_TEXT] = json!(quick);
        // 位置越界：只有那一个工具换回默认
        v["geometry"]["landscape"]["voice"]["k"] = json!(9.0);
        v["geometry"]["portrait"]["keymouse"]["x"] = json!(0.3);
        v["geometry"]["portrait"]["ball"] = json!({"x": -1.0, "y": 0.5});
        v["slots"]["voiceRow"] = json!(["key:backspace", "key:backspace", "key:space"]);
        assert!(normalize(&v, &ids).is_err());
        c.extra.insert(KEY.into(), v);
        assert!(ensure(&mut c));
        let out = c.extra[KEY].clone();
        assert!(normalize(&out, &official_button_ids(&c)).is_ok());
        assert!(c.extra.get(PREVIOUS_KEY).is_none(), "修得好就不算重建");
        assert_eq!(out["slots"]["ball"][3], Value::Null);
        assert_eq!(out["slots"]["ball"][4], Value::Null);
        assert_eq!(out["slots"]["ball"][6], alt_e);
        assert_eq!(out["slots"]["rail"], json!(["key:esc", format!("button:{}", mine[0])]));
        let quick = out["slots"][QUICK_TEXT].as_array().unwrap();
        assert_eq!(quick.len(), MAX_ROW);
        assert_eq!(quick[0], json!(format!("button:{}", mine[1])));
        assert_eq!(out["slots"]["voiceRow"], json!(["key:backspace", "key:space"]));
        let defaults = default_geometry();
        assert_eq!(out["geometry"]["landscape"]["voice"], defaults["landscape"]["voice"]);
        assert_eq!(out["geometry"]["landscape"]["keymouse"], defaults["landscape"]["keymouse"]);
        assert_eq!(out["geometry"]["portrait"]["keymouse"]["x"], 0.3);
        assert_eq!(out["geometry"]["portrait"]["ball"], defaults["portrait"]["ball"]);
        // 键鼠条、默认热键都没动
        assert!(out["slots"]["keymouse"].as_array().unwrap().contains(&json!("fn:captureSystem")));
    }

    /// 悬浮球少了几格、多了几格、固定的三个不见了：补齐、截掉、放回去
    #[test]
    fn repair_restores_ball_shape_and_fixed_functions() {
        let ids = ids(&["b1", "b2"]);
        let mut v = layout();
        v["slots"]["ball"] = json!(["fn:taskSwitch", "button:b1"]);
        v["slots"]["rail"] = json!(["fn:keymouse"]);
        let out = repair(&v, &ids).unwrap();
        let ball = out["slots"]["ball"].as_array().unwrap();
        assert_eq!(ball.len(), BALL_SLOTS);
        assert_eq!(&ball[..5], &[json!("fn:taskSwitch"), json!("button:b1"), json!("fn:voice"), json!("fn:keymouse"), json!("fn:pointer")]);
        assert_eq!(out["slots"]["rail"], json!([]));
        // 悬浮球满了、又没有固定的：换掉最后几个不固定的
        let mut v = layout();
        v["slots"]["ball"] = json!(["fn:taskSwitch", "fn:captureWechat", "fn:captureQq", "fn:sendFile",
            "key:enter", "key:esc", "key:tab", "key:space", "fn:voice"]);
        v["slots"]["keymouse"] = json!(["key:backspace"]);
        let out = repair(&v, &ids).unwrap();
        let ball = out["slots"]["ball"].as_array().unwrap();
        assert_eq!(ball.len(), BALL_SLOTS);
        for f in FIXED_FUNCTIONS {
            assert!(ball.contains(&json!(format!("fn:{f}"))), "{f}");
        }
        assert_eq!(ball[0], "fn:taskSwitch");
        // 结构用不了：不修
        for broken in [json!(null), json!({"schemaVersion": 2, "slots": {"ball": []}}), json!({"schemaVersion": 3, "slots": {}}),
            json!({"schemaVersion": 3, "slots": {"ball": "x"}})] {
            assert!(repair(&broken, &ids).is_none(), "{broken}");
        }
    }
}
