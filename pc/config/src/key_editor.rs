//! Small, reusable editor for ordinary official button key/chord mappings.
//! No remote-workbench action registry, disk writes, or input execution here.
use crate::{key_code, Action, ButtonMapping, Config, Module, ModuleKind, Step};

/// Saved text belongs to the same official Button/Action/Step model as chords.
/// Bound input size avoids creating an unbounded LAN message or surprise paste.
pub const MAX_QUICK_TEXT_CHARS: usize = 2000;

fn quick_text_action(text: &str) -> Result<Action, String> {
    let count = text.chars().count();
    if count == 0 || count > MAX_QUICK_TEXT_CHARS ||
        text.chars().all(char::is_whitespace) ||
        text.chars().any(|c| c.is_control() && c != '\n' && c != '\t') {
        return Err("常用文本需要1至2000字，允许换行与制表符，不允许控制字符".into());
    }
    Ok(Action::Paste { text: text.to_owned(), mode: None })
}

/// 「打完按回车」：粘贴完先等这么久再按回车，和正式版预设「输入文本并回车」一样。
/// 微信、Electron 这类程序是慢慢处理粘贴的，回车紧跟着到的话可能先发出一条空消息，文字还留在输入框里
pub const ENTER_DELAY_MS: u64 = 30;
/// 中间的等待最长多少还算「打完按回车」（电脑上把它改得更长，就是用户自己调过的动作，手机不再当常用文本改）
const MAX_ENTER_DELAY_MS: u64 = 100;

/// 「打完按回车」的后两步：等 30 毫秒、单独一个 Enter 键（正式版照常执行）
fn enter_steps() -> [Step; 2] {
    [wait_step(), Step::new(Action::Key { key: "Enter".into() })]
}

fn wait_step() -> Step {
    Step::new(Action::Delay { ms: ENTER_DELAY_MS })
}

fn is_enter(step: &Step) -> bool {
    step.enabled && matches!(&step.action, Action::Key { key } if key_code(key) == Some(0x0D))
}

fn is_short_wait(step: &Step) -> bool {
    step.enabled && matches!(step.action, Action::Delay { ms } if ms <= MAX_ENTER_DELAY_MS)
}

/// 手机能编辑的常用文本按键，单击是下面三种之一（每一步都启用）：
/// - 只有一步 Paste（不按回车）；
/// - Paste + Enter（以前的「打完按回车」）；
/// - Paste + 等一会儿（不超过 100 毫秒）+ Enter（现在新建、修改时写成这样）。
/// 返回（Paste 那一步，文字，打完按不按回车）
pub fn editable_text(module: &Module) -> Option<(&Step, &str, bool)> {
    if module.kind != ModuleKind::Button {
        return None;
    }
    let (first, enter) = match module.behaviors.click.as_slice() {
        [first] => (first, false),
        [first, enter] if is_enter(enter) => (first, true),
        [first, wait, enter] if is_short_wait(wait) && is_enter(enter) => (first, true),
        _ => return None,
    };
    match &first.action {
        Action::Paste { text, .. } if first.enabled => Some((first, text.as_str(), enter)),
        _ => None,
    }
}

/// 请求里的「打完按回车」：没带是不按
fn enter_flag(request: &serde_json::Value) -> Result<bool, String> {
    match request.get("enter") {
        None | Some(serde_json::Value::Null) => Ok(false),
        Some(value) => value.as_bool().ok_or_else(|| "打完按回车的格式无效".to_string()),
    }
}

/// createText 的 workbenchRow：只认 "quickText"（建好顺手放进工作台的快捷发送）
fn workbench_row(request: &serde_json::Value) -> Result<bool, String> {
    match request.get("workbenchRow") {
        None | Some(serde_json::Value::Null) => Ok(false),
        Some(serde_json::Value::String(row)) if row == crate::workbench::QUICK_TEXT => Ok(true),
        Some(_) => Err("不支持的工作台位置".into()),
    }
}

fn valid_button_label(label: &str) -> Result<&str, String> {
    let label = label.trim();
    if label.is_empty() || label.chars().count() > 80 ||
        label.chars().any(char::is_control) {
        Err("按钮名称需要1至80个可显示字符".into())
    } else { Ok(label) }
}

/// Build an ordinary key/chord using the official key parser.
/// Reject duplicate physical keys (including aliases) and empty/invalid chords.
pub fn key_action(keys: &[String]) -> Result<Action, String> {
    if keys.is_empty() || keys.len() > 5 {
        return Err("快捷键需要1至5个按键".into());
    }
    let mut codes = std::collections::HashSet::new();
    let mut normalized = Vec::with_capacity(keys.len());
    for key in keys {
        let name = key.trim();
        let code = key_code(name).ok_or("不支持的按键")?;
        if !codes.insert(code) { return Err("快捷键包含重复按键".into()); }
        normalized.push(name.to_owned());
    }
    Ok(if normalized.len() == 1 {
        Action::Key { key: normalized.remove(0) }
    } else {
        Action::Shortcut { keys: normalized }
    })
}

impl Config {
    /// Create a regular official button that inserts a reusable text snippet.
    /// The text is *not executed* during creation. It is typed/pasted only
    /// when the button is clicked, using the user's existing input setting.
    /// enter：打完按回车（Paste 后面再加「等 30 毫秒」「Enter」两步）。
    /// 新按键的映射放进每一个方案（见 share_new_button），切了方案也能用
    pub fn create_text_button(&mut self, page_id: &str, label: &str, text: &str, enter: bool) -> Result<String, String> {
        let action = quick_text_action(text)?;
        let label = valid_button_label(label)?;
        let page = self.layout.pages.iter().position(|p| p.id == page_id)
            .ok_or("页面不存在")?;
        // 明确说了建在哪一页：工作台专用页也能放（free_spot 不往专用页放，是给「添加按键」用的）
        let (x, y) = self.free_spot_in(page, 1.0, 1.0).ok_or("页面没有空位")?;
        let id = self.new_id("button");
        let mut module = Module {
            id: id.clone(), kind: ModuleKind::Button, label: label.into(),
            x, y, w: 1.0, h: 1.0, ..Default::default()
        };
        module.behaviors.click.push(Step::new(action));
        if enter {
            module.behaviors.click.extend(enter_steps());
        }
        self.layout.pages[page].modules.push(module);
        self.sync_active_profile();
        self.share_new_button(&id);
        Ok(id)
    }

    /// Optimistic edit: only replace the Paste action of an editable text button
    /// (see editable_text). Never replace other multi-step actions or alter other triggers.
    /// enter 变了就加上或去掉后面的「等一会儿 + Enter」（Paste 那一步的 id 不变）；
    /// 以前的「Paste + Enter」改的时候还要按回车，就顺手补上中间的等待。
    /// 别的方案里和改之前一模一样的映射（新建时抄过去、没单独改过）跟着改，见 follow_edit
    #[allow(clippy::too_many_arguments)]
    pub fn edit_text_button(
        &mut self, profile_id: &str, id: &str, expected: &Step,
        label: &str, expected_label: &str, text: &str, enter: bool,
    ) -> Result<(), String> {
        let action = quick_text_action(text)?;
        let label = valid_button_label(label)?;
        if self.active_profile_id != profile_id {
            return Err("当前方案已切换，请重新读取".into());
        }
        let module = self.layout.pages.iter_mut().flat_map(|p| &mut p.modules)
            .find(|m| m.id == id && m.kind == ModuleKind::Button).ok_or("按键不存在")?;
        if module.label != expected_label { return Err("按钮名称已被其他设备修改，请重新读取".into()); }
        let had_enter = match editable_text(module) {
            Some((step, _, had_enter)) if step == expected => had_enter,
            _ => return Err("动作已变更或不是常用文本，请重新读取".into()),
        };
        let before = ButtonMapping::of(module);
        let steps = &mut module.behaviors.click;
        steps[0].action = action;
        match (had_enter, enter) {
            (false, true) => steps.extend(enter_steps()),
            (true, false) => steps.truncate(1),
            (true, true) if steps.len() == 2 => steps.insert(1, wait_step()),
            _ => {}
        }
        module.label = label.into();
        self.sync_active_profile();
        self.follow_edit(id, &before);
        Ok(())
    }

    /// 删掉一个常用文本按键（手机上删快捷发送时）：从所在页拿掉，各方案里它的映射、工作台里指向它的引用一起去掉。
    /// 只删能编辑的常用文本（见 editable_text），别的按键一律拒绝；名字被别处改过也拒绝
    pub fn delete_text_button(&mut self, profile_id: &str, id: &str, expected_label: &str) -> Result<(), String> {
        if self.active_profile_id != profile_id {
            return Err("当前方案已切换，请重新读取".into());
        }
        let module = self.layout.pages.iter().flat_map(|p| &p.modules)
            .find(|m| m.id == id && m.kind == ModuleKind::Button).ok_or("按键不存在")?;
        if editable_text(module).is_none() {
            return Err("只能删除常用文本按键".into());
        }
        if module.label != expected_label {
            return Err("按钮名称已被其他设备修改，请重新读取".into());
        }
        for page in &mut self.layout.pages {
            page.modules.retain(|m| m.id != id);
        }
        for profile in &mut self.profiles {
            profile.buttons.remove(id);
        }
        crate::workbench::prune_missing(self);
        Ok(())
    }

    /// Create an official Button on an explicitly selected existing page.
    /// A full page is an error, not permission to move existing modules.
    pub fn create_key_button(&mut self, page_id: &str, label: &str, keys: &[String]) -> Result<String, String> {
        let action = key_action(keys)?;
        let label = label.trim();
        if label.is_empty() || label.chars().count() > 80 || label.chars().any(char::is_control) {
            return Err("按钮名称需要1至80个字符".into());
        }
        let page = self.layout.pages.iter().position(|p| p.id == page_id)
            .ok_or("页面不存在")?;
        // 同 create_text_button：明确说了建在哪一页，专用页也能放
        let (x, y) = self.free_spot_in(page, 1.0, 1.0).ok_or("页面没有空位")?;
        let id = self.new_id("button");
        let mut module = Module {
            id: id.clone(), kind: ModuleKind::Button, label: label.into(),
            x, y, w: 1.0, h: 1.0, ..Default::default()
        };
        module.behaviors.click.push(Step::new(action));
        self.layout.pages[page].modules.push(module);
        self.sync_active_profile();
        self.share_new_button(&id);
        Ok(id)
    }

    /// Edit a single ordinary click mapping, using the original step as an
    /// optimistic precondition. Never replace sequences or other triggers.
    /// 别的方案里和改之前一模一样的映射跟着改（同 edit_text_button）
    pub fn edit_key_button(&mut self, profile_id: &str, id: &str, expected: &Step, keys: &[String]) -> Result<(), String> {
        let action = key_action(keys)?;
        if self.active_profile_id != profile_id { return Err("当前方案已切换，请重新读取".into()); }
        let module = self.layout.pages.iter_mut().flat_map(|p| &mut p.modules)
            .find(|m| m.id == id && m.kind == ModuleKind::Button).ok_or("按键不存在")?;
        let before = ButtonMapping::of(module);
        let steps = &mut module.behaviors.click;
        if steps.len() != 1 || &steps[0] != expected || !steps[0].enabled
            || !matches!(&steps[0].action, Action::Key { .. } | Action::Shortcut { .. }) {
            return Err("动作已变更或不是单个快捷键，请重新读取".into());
        }
        steps[0].action = action;
        self.sync_active_profile();
        self.follow_edit(id, &before);
        Ok(())
    }
}

/// Only ordinary key/chord steps are exposed, not arbitrary saved scripts or text.
pub fn editor_payload(config: &Config) -> serde_json::Value {
    use serde_json::json;
    let presets: Vec<_> = crate::presets::presets().into_iter()
        .filter_map(|p| match p.actions.as_slice() {
            [action] => action.chord().filter(|keys| key_action(keys).is_ok())
                .map(|keys| json!({"group": p.group, "label": p.name, "keys": keys})),
            _ => None,
        }).collect();
    let groups: Vec<_> = crate::KEY_GROUPS.iter().map(|(label, keys)| {
        json!({"label": label, "keys": keys.iter().map(|(key, label)| json!({"key": key, "label": label})).collect::<Vec<_>>()})
    }).collect();
    let editable: Vec<_> = config.layout.pages.iter().flat_map(|p| &p.modules)
        .filter_map(|m| {
            if m.kind != ModuleKind::Button { return None; }
            match m.behaviors.click.as_slice() {
                [step] if step.enabled => step.action.chord().filter(|keys| key_action(keys).is_ok())
                    .map(|keys| json!({"id": m.id, "label": m.label, "step": step, "keys": keys})),
                _ => None,
            }
        }).collect();
    // Mobile can edit only Paste buttons, optionally followed by one Enter key
    // (enter: true). Keep text in the authenticated config payload, never in
    // logs, labels, or a second registry. step is the Paste step (expectedStep).
    let editable_text: Vec<_> = config.layout.pages.iter().flat_map(|p| &p.modules)
        .filter_map(|m| editable_text(m).map(|(step, text, enter)| json!({
            "id": m.id, "label": m.label, "step": step, "text": text, "enter": enter
        }))).collect();
    json!({"version": 1, "groups": groups, "presets": presets,
        "editable": editable, "editableText": editable_text})
}

/// Apply a restricted phone request in memory; the Core is responsible for
/// persisting the resulting Config and broadcasting it. No input is executed.
pub fn apply_request(config: &mut Config, request: &serde_json::Value) -> Result<String, String> {
    let field = |name: &str| request.get(name).and_then(serde_json::Value::as_str)
        .ok_or_else(|| format!("缺少或无效字段：{name}"));
    let profile = field("profileId")?;
    if profile != config.active_profile_id { return Err("当前方案已切换，请重新读取".into()); }
    let request_id = field("requestId")?;
    if request_id.is_empty() || request_id.len() > 100 || !request_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        return Err("请求ID无效".into());
    }
    // Text snippets reuse official Paste actions. They never accept a raw
    // arbitrary Action from the handset, and are never executed on Save.
    let operation = field("operation")?;
    if operation == "createText" {
        let text = field("text")?;
        let action = quick_text_action(text)?;
        let label = valid_button_label(field("label")?)?;
        let page_id = field("pageId")?;
        let enter = enter_flag(request)?;
        let into_quick_text = workbench_row(request)?;
        for page in &config.layout.pages {
            for module in &page.modules {
                if module.extra.get("keyEditorRequestId")
                    .and_then(serde_json::Value::as_str) == Some(request_id) {
                    let same = editable_text(module)
                        .is_some_and(|(step, _, had_enter)| step.action == action && had_enter == enter);
                    if page.id == page_id && module.label == label && same {
                        return Ok(module.id.clone());
                    }
                    return Err("该请求已处理，请重新读取配置".into());
                }
            }
        }
        if page_id == "remote-shortcuts" &&
            !config.layout.pages.iter().any(|p| p.id == page_id) {
            config.layout.pages.push(crate::Page {
                id: page_id.into(), name: "远程快捷键".into(), modules: Vec::new()
            });
        }
        let id = config.create_text_button(page_id, label, text, enter)?;
        let module = config.layout.pages.iter_mut().flat_map(|p| &mut p.modules)
            .find(|m| m.id == id).expect("new official text module");
        module.extra.insert("keyEditorRequestId".into(),
            serde_json::Value::String(request_id.into()));
        // 同一次写配置里放进工作台快捷发送的最后；工作台不合格、这一排满了就只建按键，回执照样成功
        if into_quick_text {
            crate::workbench::append_quick_text(config, &id);
        }
        return Ok(id);
    }
    if operation == "editText" {
        let id = field("buttonId")?;
        let expected: Step = serde_json::from_value(request.get("expectedStep").cloned()
            .ok_or("缺少原动作")?).map_err(|_| "原动作格式无效")?;
        config.edit_text_button(profile, id, &expected, field("label")?,
            field("expectedLabel")?, field("text")?, enter_flag(request)?)?;
        return Ok(id.to_owned());
    }
    if operation == "deleteText" {
        let id = field("buttonId")?;
        config.delete_text_button(profile, id, field("expectedLabel")?)?;
        return Ok(id.to_owned());
    }
    let keys = request.get("keys").and_then(serde_json::Value::as_array)
        .ok_or("缺少按键列表")?;
    if keys.is_empty() || keys.len() > 5 { return Err("快捷键需要1至5个按键".into()); }
    let keys: Vec<String> = keys.iter().map(|v| v.as_str().filter(|s| s.len() <= 32)
        .map(str::to_owned).ok_or("按键格式无效")).collect::<Result<_, _>>()?;
    let action = key_action(&keys)?;
    match field("operation")? {
        "create" => {
            let page_id = field("pageId")?;
            let label = field("label")?;
            // A retried create must not add a second button after a lost ACK.
            for page in &config.layout.pages {
                for module in &page.modules {
                    if module.extra.get("keyEditorRequestId").and_then(serde_json::Value::as_str) == Some(request_id) {
                        if page.id == page_id && module.label == label.trim() && module.kind == ModuleKind::Button
                            && module.behaviors.click.len() == 1 && module.behaviors.click[0].enabled
                            && module.behaviors.click[0].action == action {
                            return Ok(module.id.clone());
                        }
                        return Err("该请求已处理，请重新读取配置".into());
                    }
                }
            }
            // A dedicated *official* page keeps remote shortcuts out of
            // the user's existing portrait home grid. No parallel action schema.
            if page_id == "remote-shortcuts" && !config.layout.pages.iter().any(|p| p.id == page_id) {
                config.layout.pages.push(crate::Page {
                    id: page_id.into(), name: "远程快捷键".into(), modules: Vec::new()
                });
            }
            let id = config.create_key_button(page_id, label, &keys)?;
            let module = config.layout.pages.iter_mut().flat_map(|p| &mut p.modules)
                .find(|m| m.id == id).expect("new module");
            module.extra.insert("keyEditorRequestId".into(), serde_json::Value::String(request_id.into()));
            Ok(id)
        }
        "edit" => {
            let id = field("buttonId")?;
            let expected: Step = serde_json::from_value(request.get("expectedStep").cloned()
                .ok_or("缺少原动作")?).map_err(|_| "原动作格式无效")?;
            // Label changes use an additional optimistic precondition. A stale
            // phone cannot silently overwrite a rename made in the PC editor.
            let rename = match request.get("label") {
                None => None, // old clients may still edit only the shortcut
                Some(value) => {
                    let name = value.as_str().ok_or("按钮名称格式无效")?.trim();
                    if name.is_empty() || name.chars().count() > 80 || name.chars().any(char::is_control) {
                        return Err("按钮名称需要1至80个可显示字符".into());
                    }
                    let old_label = field("expectedLabel")?;
                    let current = config.layout.pages.iter().flat_map(|p| &p.modules)
                        .find(|m| m.id == id && m.kind == ModuleKind::Button)
                        .ok_or("按键不存在")?;
                    if current.label != old_label { return Err("按钮名称已被其他设备修改，请重新读取".into()); }
                    Some(name.to_owned())
                }
            };
            config.edit_key_button(profile, id, &expected, &keys)?;
            if let Some(label) = rename {
                let module = config.layout.pages.iter_mut().flat_map(|p| &mut p.modules)
                    .find(|m| m.id == id && m.kind == ModuleKind::Button)
                    .expect("validated button still exists");
                module.label = label;
            }
            Ok(id.to_owned())
        }
        _ => Err("不支持的编辑操作".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Page, Trigger};
    fn keys(values: &[&str]) -> Vec<String> { values.iter().map(|s| s.to_string()).collect() }

    #[test]
    fn ordinary_actions_use_existing_parser() {
        assert_eq!(key_action(&keys(&["Ctrl", "A"])).unwrap(), Action::Shortcut { keys: keys(&["Ctrl", "A"]) });
        assert_eq!(key_action(&keys(&["Enter"])).unwrap(), Action::Key { key: "Enter".into() });
        assert!(key_action(&keys(&["Alt", "LAlt"])).is_err());
        assert!(key_action(&keys(&["Ctrl", "NotAKey"])).is_err());
        assert!(key_action(&[]).is_err());
    }

    #[test]
    fn creates_official_button_and_round_trips_profile() {
        let mut c = Config::fresh();
        let before = serde_json::to_value(&c.layout.pages[0]).unwrap();
        c.layout.pages.push(Page { id: "test-page".into(), name: "Test".into(), modules: vec![] });
        let id = c.create_key_button("test-page", "Select all", &keys(&["Ctrl", "A"])).unwrap();
        assert_eq!(serde_json::to_value(&c.layout.pages[0]).unwrap(), before);
        let mut decoded: Config = serde_json::from_value(serde_json::to_value(&c).unwrap()).unwrap();
        decoded.normalize();
        let module = &decoded.layout.pages[1].modules[0];
        assert_eq!(module.id, id);
        assert_eq!(module.behaviors.get(Trigger::Click)[0].action, Action::Shortcut { keys: keys(&["Ctrl", "A"]) });
        assert!(decoded.active_profile().unwrap().buttons.contains_key(&id));
        let payload = decoded.phone_payload();
        assert_eq!(payload["layout"]["pages"][1]["modules"][0]["id"], id);
        assert_eq!(payload["layout"]["pages"][1]["modules"][0]["triggers"]["click"], true);
    }

    #[test]
    fn edits_same_button_without_destroying_other_triggers_and_rejects_stale_edit() {
        let mut c = Config::fresh();
        let profile = c.active_profile_id.clone();
        let original = c.layout.pages[0].modules.iter().find(|m| m.id == "esc").unwrap().behaviors.click[0].clone();
        c.edit_key_button(&profile, "esc", &original, &keys(&["Alt", "E"])).unwrap();
        let snapshot = serde_json::to_value(&c).unwrap();
        assert!(c.edit_key_button(&profile, "esc", &original, &keys(&["Ctrl", "A"])).is_err());
        assert!(c.edit_key_button("stale-profile", "esc", &original, &keys(&["Ctrl", "A"])).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), snapshot);
        let module = c.layout.pages[0].modules.iter().find(|m| m.id == "esc").unwrap();
        assert_eq!(module.behaviors.click[0].id, original.id);
        assert_eq!(module.behaviors.click[0].action, Action::Shortcut { keys: keys(&["Alt", "E"]) });
    }

    #[test]
    fn phone_request_roundtrip_and_duplicate_create() {
        use serde_json::json;
        let mut c = Config::fresh();
        let request = json!({"requestId":"request-1","profileId":c.active_profile_id,
            "operation":"create","pageId":"remote-shortcuts","label":"Select all","keys":["Ctrl","A"]});
        let id = apply_request(&mut c, &request).unwrap();
        let snapshot = serde_json::to_value(&c).unwrap();
        assert_eq!(apply_request(&mut c, &request).unwrap(), id);
        assert_eq!(serde_json::to_value(&c).unwrap(), snapshot);
        let payload = c.phone_payload();
        let item = payload["keyEditor"]["editable"].as_array().unwrap().iter()
            .find(|m| m["id"] == id).unwrap();
        assert_eq!(item["keys"], json!(["Ctrl","A"]));
        assert_eq!(item["label"], "Select all");
        let edit = json!({"requestId":"request-2","profileId":c.active_profile_id,
            "operation":"edit","buttonId":id,"expectedStep":item["step"],
            "expectedLabel":"Select all","label":"Alt+E","keys":["Alt","E"]});
        assert_eq!(apply_request(&mut c, &edit).unwrap(), id);
        assert_eq!(c.layout.pages.last().unwrap().modules[0].label, "Alt+E");
        assert!(apply_request(&mut c, &edit).is_err());
        let saved = serde_json::to_value(&c).unwrap();
        let mut invalid = request.clone();
        invalid["requestId"] = json!("request-3");
        invalid["profileId"] = json!("stale");
        assert!(apply_request(&mut c, &invalid).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), saved);
    }

    #[test]
    fn stale_label_or_bad_new_label_cannot_overwrite_official_button() {
        use serde_json::json;
        let mut c = Config::fresh();
        let old = c.layout.pages[0].modules.iter().find(|m| m.id == "esc").unwrap();
        let expected = serde_json::to_value(&old.behaviors.click[0]).unwrap();
        let profile = c.active_profile_id.clone();
        let base = json!({"requestId":"rename-1", "profileId":profile,
            "operation":"edit", "buttonId":"esc", "expectedStep": expected,
            "expectedLabel":"not the original label", "label":"New name", "keys":["Alt","E"]});
        let before = serde_json::to_value(&c).unwrap();
        assert!(apply_request(&mut c, &base).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), before);
        let mut invalid = base.clone();
        invalid["expectedLabel"] = json!("Esc");
        invalid["label"] = json!("Bad\nname");
        assert!(apply_request(&mut c, &invalid).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), before);
        invalid["label"] = json!("Safe name");
        assert_eq!(apply_request(&mut c, &invalid).unwrap(), "esc");
        assert_eq!(c.layout.pages[0].modules.iter().find(|m| m.id == "esc").unwrap().label, "Safe name");
    }

    #[test]
    fn phone_catalog_uses_original_presets_without_sequences() {
        let p = editor_payload(&Config::fresh());
        let presets = p["presets"].as_array().unwrap();
        for keys in [serde_json::json!(["Ctrl","A"]), serde_json::json!(["Alt","A"]), serde_json::json!(["Alt","E"])] {
            assert!(presets.iter().any(|p| p["keys"] == keys));
        }
        assert!(!presets.iter().any(|p| p["label"].as_str().unwrap().contains("删除全部文本")));
        assert!(p["groups"].as_array().unwrap().len() > 0);
    }

    #[test]
    fn quick_text_uses_official_paste_action_without_auto_enter() {
        use serde_json::json;
        let mut c = Config::fresh();
        let request = json!({"requestId":"text-1","profileId":c.active_profile_id,
            "operation":"createText","pageId":"remote-shortcuts",
            "label":"常用回复","text":"好的，稍后回复。\n谢谢！"});
        let id = apply_request(&mut c, &request).unwrap();
        let module = c.layout.pages.iter().flat_map(|p| &p.modules)
            .find(|m| m.id == id).unwrap();
        let expected = "好的，稍后回复。\n谢谢！";
        assert_eq!(module.behaviors.click.len(), 1);
        assert_eq!(module.behaviors.click[0].action,
            Action::Paste { text: expected.into(), mode: None });
        let saved = serde_json::to_value(&c).unwrap();
        assert_eq!(apply_request(&mut c, &request).unwrap(), id);
        assert_eq!(serde_json::to_value(&c).unwrap(), saved);
        let editable = c.phone_payload()["keyEditor"]["editableText"]
            .as_array().unwrap().iter().find(|x| x["id"] == id).unwrap().clone();
        assert_eq!(editable["text"], expected);
        let edit = json!({"requestId":"text-2","profileId":c.active_profile_id,
            "operation":"editText","buttonId":id,"expectedStep":editable["step"],
            "expectedLabel":"常用回复","label":"工作回复","text":"确认收到"});
        assert_eq!(apply_request(&mut c, &edit).unwrap(), id);
        let edited = c.layout.pages.iter().flat_map(|p| &p.modules)
            .find(|m| m.id == id).unwrap();
        assert_eq!(edited.label, "工作回复");
        assert_eq!(edited.behaviors.click[0].action,
            Action::Paste { text: "确认收到".into(), mode: None });
        assert!(apply_request(&mut c, &edit).is_err());
    }

    #[test]
    fn quick_text_rejects_unsafe_or_stale_requests_without_mutation() {
        use serde_json::json;
        let mut c = Config::fresh();
        let snapshot = serde_json::to_value(&c).unwrap();
        for bad in ["", "  ", "\u{0000}", "\u{0008}", &"字".repeat(2001)] {
            let request = json!({"requestId":"bad-1","profileId":c.active_profile_id,
                "operation":"createText","pageId":"remote-shortcuts",
                "label":"测试","text":bad});
            assert!(apply_request(&mut c, &request).is_err());
            assert_eq!(serde_json::to_value(&c).unwrap(), snapshot);
        }
        let invalid = json!({"requestId":"other","profileId":"old-profile",
            "operation":"createText","pageId":"remote-shortcuts",
            "label":"测试","text":"你好"});
        assert!(apply_request(&mut c, &invalid).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), snapshot);
    }

    #[test]
    fn rejects_full_page_without_mutation() {
        let mut c = Config::fresh();
        let before = serde_json::to_value(&c).unwrap();
        assert!(c.create_key_button("main", "Select all", &keys(&["Ctrl", "A"])).is_err());
        assert!(c.create_key_button("missing", "Select all", &keys(&["Ctrl", "A"])).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), before);
    }

    /// 手机发来的 createText（文字和名字一样），extra 里的字段加上去
    fn text_request(profile: &str, request_id: &str, label: &str, extra: serde_json::Value) -> serde_json::Value {
        let mut request = serde_json::json!({"requestId": request_id, "profileId": profile,
            "operation": "createText", "pageId": "remote-shortcuts", "label": label, "text": label});
        for (k, v) in extra.as_object().unwrap() {
            request[k] = v.clone();
        }
        request
    }

    fn editable_item(c: &Config, id: &str) -> serde_json::Value {
        c.phone_payload()["keyEditor"]["editableText"].as_array().unwrap().iter()
            .find(|x| x["id"] == id).cloned().expect("editable text item")
    }

    /// 打完按回车 = Paste、等 30 毫秒、Enter（和正式版预设「输入文本并回车」一样）；不按回车的还是一步。
    /// 两种都是手机能编辑的常用文本
    #[test]
    fn quick_text_enter_option_adds_enter_step() {
        use serde_json::json;
        let mut c = Config::fresh();
        let p = c.active_profile_id.clone();
        let plain = apply_request(&mut c, &text_request(&p, "plain-1", "收到", json!({}))).unwrap();
        let module = c.find_module(&plain).unwrap();
        assert_eq!(module.behaviors.click.len(), 1);
        assert_eq!(editable_item(&c, &plain)["enter"], false);
        let request = text_request(&p, "enter-1", "发出去了", json!({"enter": true}));
        let id = apply_request(&mut c, &request).unwrap();
        let module = c.find_module(&id).unwrap();
        let actions: Vec<&Action> = module.behaviors.click.iter().map(|s| &s.action).collect();
        assert_eq!(actions, [
            &Action::Paste { text: "发出去了".into(), mode: None },
            &Action::Delay { ms: ENTER_DELAY_MS },
            &Action::Key { key: "Enter".into() },
        ]);
        // 和正式版预设「输入文本并回车」的三步一样（文字除外）
        let preset = crate::presets::presets().into_iter().find(|p| p.name.starts_with("输入文本并回车")).unwrap();
        assert_eq!(preset.actions[1..], [Action::Delay { ms: ENTER_DELAY_MS }, Action::Key { key: "Enter".into() }]);
        let item = editable_item(&c, &id);
        assert_eq!(item["enter"], true);
        assert_eq!(item["text"], "发出去了");
        assert_eq!(item["step"]["type"], "paste");
        // 回执丢了重发：不多建；同一个请求号内容对不上（不按回车）：拒绝
        let saved = serde_json::to_value(&c).unwrap();
        assert_eq!(apply_request(&mut c, &request).unwrap(), id);
        let mut other = request.clone();
        other["enter"] = json!(false);
        assert!(apply_request(&mut c, &other).is_err());
        let mut bad = text_request(&p, "enter-2", "测试", json!({"enter": "yes"}));
        assert!(apply_request(&mut c, &bad).is_err());
        bad["enter"] = json!(true);
        bad["workbenchRow"] = json!("rail");
        assert!(apply_request(&mut c, &bad).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), saved);
        // 最后不是单独一个回车的按键、回车停用了、中间等太久、中间不是等待：都不算常用文本
        let module = c.find_module(&id).unwrap().clone();
        let mut chord = module.clone();
        chord.behaviors.click[2].action = Action::Shortcut { keys: keys(&["Ctrl", "Enter"]) };
        assert!(editable_text(&chord).is_none());
        let mut off = module.clone();
        off.behaviors.click[2].enabled = false;
        assert!(editable_text(&off).is_none());
        let mut slow = module.clone();
        slow.behaviors.click[1].action = Action::Delay { ms: 500 };
        assert!(editable_text(&slow).is_none());
        let mut other = module.clone();
        other.behaviors.click[1].action = Action::Key { key: "Tab".into() };
        assert!(editable_text(&other).is_none());
        let mut wait_off = module;
        wait_off.behaviors.click[1].enabled = false;
        assert!(editable_text(&wait_off).is_none());
    }

    /// 认三种：只有 Paste；Paste + Enter（以前写的）；Paste + 等待 + Enter。后两种 enter = true
    #[test]
    fn editable_text_accepts_three_shapes() {
        let mut c = Config::fresh();
        c.layout.pages.push(crate::Page { id: "t".into(), name: "T".into(), modules: vec![] });
        let id = c.create_text_button("t", "好的", "好的", false).unwrap();
        let mut module = c.find_module(&id).unwrap().clone();
        assert_eq!(editable_text(&module).map(|(_, text, enter)| (text, enter)), Some(("好的", false)));
        module.behaviors.click.push(Step::new(Action::Key { key: "Return".into() }));
        assert_eq!(editable_text(&module).map(|(_, text, enter)| (text, enter)), Some(("好的", true)));
        module.behaviors.click.insert(1, Step::new(Action::Delay { ms: 100 }));
        assert_eq!(editable_text(&module).map(|(_, text, enter)| (text, enter)), Some(("好的", true)));
        // 第一步不是 Paste、或者 Paste 停用了：不算
        module.behaviors.click[0].enabled = false;
        assert!(editable_text(&module).is_none());
        module.behaviors.click[0] = Step::new(Action::Key { key: "A".into() });
        assert!(editable_text(&module).is_none());
    }

    /// 「打完按回车」开关从哪一种开始都能切：以前的「Paste + Enter」关掉变一步，
    /// 开着再改一次文字就补上中间的等待；三步的那种开着改，等待和回车那两步原样留着
    #[test]
    fn edit_text_toggles_enter_from_any_shape() {
        use serde_json::json;
        let mut c = Config::fresh();
        let p = c.active_profile_id.clone();
        let old = apply_request(&mut c, &text_request(&p, "o-1", "旧的", json!({"enter": true}))).unwrap();
        // 做成以前的两步（Paste + Enter）
        let module = c.layout.pages.iter_mut().flat_map(|pg| &mut pg.modules).find(|m| m.id == old).unwrap();
        module.behaviors.click.remove(1);
        c.sync_active_profile();
        let paste_id = c.find_module(&old).unwrap().behaviors.click[0].id.clone();
        let enter_id = c.find_module(&old).unwrap().behaviors.click[1].id.clone();
        assert_eq!(editable_item(&c, &old)["enter"], true);
        let edit = |c: &Config, request_id: &str, text: &str, enter: bool| {
            let item = editable_item(c, &old);
            json!({"requestId": request_id, "profileId": p, "operation": "editText", "buttonId": old,
                "expectedStep": item["step"], "expectedLabel": "旧的", "label": "旧的", "text": text, "enter": enter})
        };
        // 开着改文字：补上等待，Paste、Enter 两步的 id 不变
        let request = edit(&c, "o-2", "新的", true);
        apply_request(&mut c, &request).unwrap();
        let steps = &c.find_module(&old).unwrap().behaviors.click;
        assert_eq!(steps.len(), 3);
        assert_eq!((steps[0].id.as_str(), steps[2].id.as_str()), (paste_id.as_str(), enter_id.as_str()));
        assert_eq!(steps[1].action, Action::Delay { ms: ENTER_DELAY_MS });
        let wait_id = steps[1].id.clone();
        // 三步的开着再改：后两步原样
        let request = edit(&c, "o-3", "再新", true);
        apply_request(&mut c, &request).unwrap();
        let steps = &c.find_module(&old).unwrap().behaviors.click;
        assert_eq!(steps.len(), 3);
        assert_eq!((steps[1].id.as_str(), steps[2].id.as_str()), (wait_id.as_str(), enter_id.as_str()));
        assert_eq!(steps[0].action, Action::Paste { text: "再新".into(), mode: None });
        // 关掉：只剩 Paste
        let request = edit(&c, "o-4", "再新", false);
        apply_request(&mut c, &request).unwrap();
        assert_eq!(c.find_module(&old).unwrap().behaviors.click.len(), 1);
        // 以前的两步直接关掉：也只剩 Paste
        let module = c.layout.pages.iter_mut().flat_map(|pg| &mut pg.modules).find(|m| m.id == old).unwrap();
        module.behaviors.click.push(Step::new(Action::Key { key: "Enter".into() }));
        c.sync_active_profile();
        let request = edit(&c, "o-5", "再新", false);
        apply_request(&mut c, &request).unwrap();
        assert_eq!(c.find_module(&old).unwrap().behaviors.click.len(), 1);
        assert_eq!(c.find_module(&old).unwrap().behaviors.click[0].id, paste_id);
    }

    /// 改常用文本时切换「打完按回车」：加上或去掉 Enter 那一步，Paste 那一步的 id 不变；过期的请求不动配置
    #[test]
    fn edit_text_toggles_enter() {
        use serde_json::json;
        let mut c = Config::fresh();
        let p = c.active_profile_id.clone();
        let id = apply_request(&mut c, &text_request(&p, "t-1", "邮箱", json!({}))).unwrap();
        let paste_id = c.find_module(&id).unwrap().behaviors.click[0].id.clone();
        let item = editable_item(&c, &id);
        let edit = json!({"requestId":"t-2","profileId":c.active_profile_id,"operation":"editText",
            "buttonId":id,"expectedStep":item["step"],"expectedLabel":"邮箱","label":"邮箱",
            "text":"me@example.com","enter":true});
        assert_eq!(apply_request(&mut c, &edit).unwrap(), id);
        let module = c.find_module(&id).unwrap();
        assert_eq!(module.behaviors.click.len(), 3);
        assert_eq!(module.behaviors.click[0].id, paste_id);
        assert_eq!(module.behaviors.click[1].action, Action::Delay { ms: ENTER_DELAY_MS });
        assert_eq!(editable_item(&c, &id)["enter"], true);
        // 原来那一步变了：旧请求拒绝，配置不动
        let saved = serde_json::to_value(&c).unwrap();
        assert!(apply_request(&mut c, &edit).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), saved);
        let item = editable_item(&c, &id);
        let off = json!({"requestId":"t-3","profileId":c.active_profile_id,"operation":"editText",
            "buttonId":id,"expectedStep":item["step"],"expectedLabel":"邮箱","label":"我的邮箱",
            "text":"me@example.com"});
        assert_eq!(apply_request(&mut c, &off).unwrap(), id);
        let module = c.find_module(&id).unwrap();
        assert_eq!(module.label, "我的邮箱");
        assert_eq!(module.behaviors.click.len(), 1);
        assert_eq!(module.behaviors.click[0].id, paste_id);
        assert_eq!(editable_item(&c, &id)["enter"], false);
        // 不是常用文本的按键不能当常用文本改
        let esc = c.find_module("esc").unwrap().behaviors.click[0].clone();
        let profile = c.active_profile_id.clone();
        assert!(c.edit_text_button(&profile, "esc", &esc, "Esc", "Esc", "x", true).is_err());
    }

    /// 带 workbenchRow 新建：同一次写配置里放进快捷发送最后；工作台没有或这一排满了就只建按键
    #[test]
    fn create_text_into_quick_text_row() {
        use serde_json::json;
        use crate::workbench;
        let mut c = Config::fresh();
        let p = c.active_profile_id.clone();
        // 还没有工作台：照样建好
        let lonely = apply_request(&mut c, &text_request(&p, "q-0", "早", json!({"workbenchRow": "quickText"}))).unwrap();
        assert!(c.find_module(&lonely).is_some());
        assert!(c.extra.get(workbench::KEY).is_none());
        assert!(workbench::ensure(&mut c));
        let first = apply_request(&mut c, &text_request(&p, "q-1", "收到", json!({"workbenchRow": "quickText"}))).unwrap();
        let second = apply_request(&mut c, &text_request(&p, "q-2", "马上看", json!({"workbenchRow": "quickText", "enter": true}))).unwrap();
        let row = c.extra[workbench::KEY]["slots"][workbench::QUICK_TEXT].clone();
        assert_eq!(row, json!([format!("button:{first}"), format!("button:{second}")]));
        // 重发同一个请求：不多放一次
        apply_request(&mut c, &text_request(&p, "q-1", "收到", json!({"workbenchRow": "quickText"}))).unwrap();
        assert_eq!(c.extra[workbench::KEY]["slots"][workbench::QUICK_TEXT], row);
        // 不带 workbenchRow：不放
        let plain = apply_request(&mut c, &text_request(&p, "q-3", "在路上", json!({}))).unwrap();
        assert_eq!(c.extra[workbench::KEY]["slots"][workbench::QUICK_TEXT], row);
        assert!(c.find_module(&plain).is_some());
    }

    /// deleteText：删掉常用文本按键和工作台里指向它的引用；别的按键、名字过期、换了方案都拒绝，配置不动
    #[test]
    fn delete_text_removes_button_and_workbench_refs() {
        use serde_json::json;
        use crate::workbench;
        let mut c = Config::fresh();
        let p = c.active_profile_id.clone();
        assert!(workbench::ensure(&mut c));
        let id = apply_request(&mut c, &text_request(&p, "d-1", "会议号", json!({"workbenchRow": "quickText", "enter": true}))).unwrap();
        let other = apply_request(&mut c, &text_request(&p, "d-2", "地址", json!({}))).unwrap();
        // 再放一份到悬浮球的空格（快捷发送里拿出来）：两处引用都要去掉
        let mut layout = c.extra[workbench::KEY].clone();
        layout["slots"][workbench::QUICK_TEXT] = json!([format!("button:{other}")]);
        layout["slots"]["ball"][7] = json!(format!("button:{id}"));
        c.extra.insert(workbench::KEY.into(), workbench::normalize(&layout, &workbench::official_button_ids(&c)).unwrap());
        assert!(c.active_profile().unwrap().buttons.contains_key(&id));
        let request = |id: &str, label: &str, request_id: &str| json!({"requestId": request_id,
            "profileId": p, "operation": "deleteText", "buttonId": id, "expectedLabel": label});
        let snapshot = serde_json::to_value(&c).unwrap();
        // 不是常用文本（Esc 键）、名字对不上、方案换了、按键不存在：都拒绝
        assert!(apply_request(&mut c, &request("esc", "Esc", "d-3")).is_err());
        assert!(apply_request(&mut c, &request(&id, "别的名字", "d-4")).is_err());
        let mut stale = request(&id, "会议号", "d-5");
        stale["profileId"] = json!("old-profile");
        assert!(apply_request(&mut c, &stale).is_err());
        assert!(apply_request(&mut c, &request("nope", "会议号", "d-6")).is_err());
        assert_eq!(serde_json::to_value(&c).unwrap(), snapshot);
        assert_eq!(apply_request(&mut c, &request(&id, "会议号", "d-7")).unwrap(), id);
        assert!(c.find_module(&id).is_none());
        assert!(!c.active_profile().unwrap().buttons.contains_key(&id));
        let v = &c.extra[workbench::KEY];
        assert_eq!(v["slots"]["ball"][7], serde_json::Value::Null);
        assert_eq!(v["slots"][workbench::QUICK_TEXT], json!([format!("button:{other}")]));
        assert!(workbench::normalize(v, &workbench::official_button_ids(&c)).is_ok());
        // 删过了再删：按键不存在
        assert!(apply_request(&mut c, &request(&id, "会议号", "d-8")).is_err());
    }

    /// 手机上新建的快捷发送、热键：每个方案里都有一份，切了方案照样能用、能改（审查 #8）。
    /// 手机上改的时候，别的方案里没单独改过的跟着改；在电脑上给某个方案单独改过的不动
    #[test]
    fn created_buttons_work_in_every_profile() {
        use serde_json::json;
        let mut c = Config::fresh();
        let work = c.add_profile("工作", None);
        let game = c.add_profile("游戏", None);
        let p = c.active_profile_id.clone();
        let text = apply_request(&mut c, &text_request(&p, "m-1", "收到", json!({"enter": true}))).unwrap();
        let hotkey = apply_request(&mut c, &json!({"requestId": "m-2", "profileId": p, "operation": "create",
            "pageId": "remote-shortcuts", "label": "全选", "keys": ["Ctrl", "A"]})).unwrap();
        for profile in &c.profiles {
            assert_eq!(profile.buttons[&text].click.len(), 3, "{}", profile.name);
            assert_eq!(profile.buttons[&hotkey].click[0].action, Action::Shortcut { keys: keys(&["Ctrl", "A"]) }, "{}", profile.name);
        }
        // 切到别的方案：还是常用文本，手机能改、能删
        c.switch_profile(&work);
        assert_eq!(editable_item(&c, &text)["text"], "收到");
        // 在「游戏」方案里单独改过（电脑上）：手机改的时候不动它
        c.profiles.iter_mut().find(|pr| pr.id == game).unwrap().buttons.get_mut(&text).unwrap().click[0].action =
            Action::Paste { text: "游戏里说的".into(), mode: None };
        let item = editable_item(&c, &text);
        let edit = json!({"requestId": "m-3", "profileId": work, "operation": "editText", "buttonId": text,
            "expectedStep": item["step"], "expectedLabel": "收到", "label": "好的", "text": "好的", "enter": true});
        apply_request(&mut c, &edit).unwrap();
        let paste = |c: &Config, profile: &str| c.profiles.iter().find(|pr| pr.id == profile).unwrap()
            .buttons[&text].click[0].action.clone();
        assert_eq!(paste(&c, &work), Action::Paste { text: "好的".into(), mode: None });
        assert_eq!(paste(&c, &p), Action::Paste { text: "好的".into(), mode: None });
        assert_eq!(paste(&c, &game), Action::Paste { text: "游戏里说的".into(), mode: None });
        // 改热键也一样：没单独改过的方案跟着改
        let step = c.find_module(&hotkey).unwrap().behaviors.click[0].clone();
        c.edit_key_button(&work, &hotkey, &step, &keys(&["Ctrl", "Shift", "A"])).unwrap();
        for profile in &c.profiles {
            assert_eq!(profile.buttons[&hotkey].click[0].action, Action::Shortcut { keys: keys(&["Ctrl", "Shift", "A"]) });
        }
        // 存成文件再读回来，三个方案都还在
        let again = crate::parse(&crate::to_text(&c)).unwrap();
        assert!(again.profiles.iter().all(|pr| pr.buttons.contains_key(&text) && pr.buttons.contains_key(&hotkey)));
    }

    /// 新建的按键 id 只增不减：删掉的 id 不会再发给新按键（审查 #3）
    #[test]
    fn deleted_ids_are_never_reused() {
        use serde_json::json;
        let mut c = Config::fresh();
        let p = c.active_profile_id.clone();
        let first = apply_request(&mut c, &text_request(&p, "r-1", "一", json!({}))).unwrap();
        apply_request(&mut c, &json!({"requestId": "r-2", "profileId": p, "operation": "deleteText",
            "buttonId": first, "expectedLabel": "一"})).unwrap();
        let second = apply_request(&mut c, &text_request(&p, "r-3", "二", json!({}))).unwrap();
        assert_ne!(first, second);
        // 写进文件再读回来（别的程序接着建）也一样
        let mut again = crate::parse(&crate::to_text(&c)).unwrap();
        apply_request(&mut again, &json!({"requestId": "r-4", "profileId": p, "operation": "deleteText",
            "buttonId": second, "expectedLabel": "二"})).unwrap();
        let third = apply_request(&mut again, &text_request(&p, "r-5", "三", json!({}))).unwrap();
        assert!(![&first, &second].contains(&&third), "{third}");
    }
}
