//! 布局页的「远程工作台」标签：手机进「电脑屏幕」全屏时用的悬浮球、语音条、键鼠条、侧栏里放什么、什么顺序
//! （位置和大小在手机上「调整布局」里拖）。改的是配置里的 screenWorkbenchV3，规则都在 suanniao_config::workbench：
//! 每改一下先生成新布局交给 normalize 检查，不合格就提示原因、什么都不改；合格就和布局页别的改动一样
//! 记撤销、自动保存，后台看到配置文件变了推给手机。
//! - 工作台的热键、常用文本是正式版按键，放在工作台专用页上。布局页不显示这一页（见 main.rs 的 visible_pages），
//!   这里每改一次都顺手删掉专用页上哪儿都没用到的按键，和这一步算同一步撤销；
//! - 按钮可以点开菜单挪，也可以按住拖着排顺序、拖到别的排（规则和手机一样）；
//! - 按钮库里能直接「＋ 新建热键」「＋ 新建常用文本」，建好放到点开按钮库的那一格。

use std::rc::Rc;

use serde_json::Value;
use suanniao_config::key_editor;
use suanniao_config::workbench::{self as wb, Place, Slots};

use super::*;

/// 五处的按钮列表：一直是同一组模型、只按行更新（刷新时不会把正点开菜单的按钮重建掉）
#[derive(Default)]
pub struct Models {
    places: [Rc<VecModel<WbChip>>; 5],
    /// 「新建热键」「新建常用文本」的对话框开着时：建好放到哪（点开按钮库的那一格）
    pending: RefCell<Option<Target>>,
}

impl Models {
    pub fn bind(&self, ui: &AppWindow) {
        let [ball, quick, voice, keymouse, rail] = &self.places;
        ui.set_wb_ball(ModelRc::from(ball.clone()));
        ui.set_wb_quick(ModelRc::from(quick.clone()));
        ui.set_wb_voice(ModelRc::from(voice.clone()));
        ui.set_wb_keymouse(ModelRc::from(keymouse.clone()));
        ui.set_wb_rail(ModelRc::from(rail.clone()));
    }
}

/// 菜单、按钮库是对着哪一格点开的：哪一处、第几个（None 是后面的「＋」）、点开那一刻那里是什么（「＋」、空格是空的）
#[derive(Clone, Debug, PartialEq)]
pub struct Target {
    place: Place,
    index: Option<usize>,
    shown: String,
}

/// 语音、键鼠、精密鼠标：固定在悬浮球里，只能挪位置
fn fixed(token: &str) -> bool {
    token.strip_prefix("fn:").is_some_and(|f| wb::FIXED_FUNCTIONS.contains(&f))
}

/// 菜单标题下面的小字：它是什么
fn detail(config: &Config, token: &str) -> &'static str {
    if fixed(token) {
        return "手机动作 · 固定在悬浮球，只能挪位置";
    }
    match token.split_once(':') {
        Some(("fn", _)) => "手机动作",
        Some(("key", _)) => "基础键",
        Some(("button", id)) => match config.find_module(id) {
            Some(m) => match key_editor::editable_text(m) {
                Some((_, _, true)) => "常用文本 · 打完按回车",
                Some(_) => "常用文本",
                None if m.extra.get("confirm").and_then(|v| v.as_bool()) == Some(true) => "正式版按键 · 按之前先确认",
                None => "正式版按键",
            },
            None => "正式版按键",
        },
        _ => "",
    }
}

fn chip(config: &Config, token: Option<&str>) -> WbChip {
    let Some(token) = token else { return WbChip::default() };
    let enter = token.strip_prefix("button:").and_then(|id| config.find_module(id)).and_then(key_editor::editable_text).is_some_and(|(_, _, enter)| enter);
    WbChip { token: token.into(), label: wb::label(config, token).into(), detail: detail(config, token).into(), fixed: fixed(token), enter }
}

fn item(text: impl Into<SharedString>, action: impl Into<SharedString>, enabled: bool) -> WbItem {
    WbItem { text: text.into(), action: action.into(), enabled, ..Default::default() }
}

fn header(text: &str) -> WbItem {
    WbItem { header: true, text: text.into(), ..Default::default() }
}

/// 按钮库里的一组：有东西才列标题
fn group(items: &mut Vec<WbItem>, title: &str, list: Vec<WbItem>) {
    if !list.is_empty() {
        items.push(header(title));
        items.extend(list);
    }
}

/// 现在的工作台布局：引用了已删按键的先去掉（删了按键、还没保存时；保存前也会这样去掉）。没有或不合格返回 None
fn current(config: &Config) -> Option<(Slots, HashSet<String>)> {
    let ids = wb::official_button_ids(config);
    let mut layout = config.extra.get(wb::KEY)?.clone();
    wb::prune_slots(&mut layout, &ids);
    let valid = wb::normalize(&layout, &ids).ok()?;
    Some((Slots::from_layout(&valid)?, ids))
}

/// 菜单是点开那一刻的样子（审查 #6）：这期间工作台可能被别处改过（手机上刚调了布局、拖动排了顺序），
/// 拿点开时记下的 shown 找它现在在哪：
/// - 「＋」：不用找；
/// - 悬浮球的空格：那一格还空着才算；
/// - 别的：那一格还是它就是它；挪到同一处别的位置就跟过去；不在这一处了返回 None（让用户再点一次）
fn locate(slots: &Slots, target: &Target) -> Option<Option<usize>> {
    let Some(index) = target.index else { return Some(None) };
    let list = slots.list(target.place);
    if target.shown.is_empty() {
        return matches!(list.get(index), Some(None)).then_some(Some(index));
    }
    let is_shown = |t: &Option<String>| t.as_deref() == Some(target.shown.as_str());
    if list.get(index).is_some_and(is_shown) {
        return Some(Some(index));
    }
    list.iter().position(is_shown).map(Some)
}

/// 一个按钮的菜单：往前挪（固定的还有往后挪）、移到别处、换成别的、改映射（正式版按键）、拿掉
fn menu_items(slots: &Slots, place: Place, index: usize) -> Option<Vec<WbItem>> {
    let list = slots.list(place);
    let token = list.get(index)?.clone()?;
    let mut items = vec![item("往前挪", "earlier", index > 0)];
    if fixed(&token) {
        items.push(item("往后挪", "later", index + 1 < list.len()));
        return Some(items);
    }
    let ball_full = !slots.list(Place::Ball).iter().any(Option::is_none);
    for target in Place::ALL {
        // 快捷发送只放正式版按键
        if target == place || (target == Place::QuickText && !token.starts_with("button:")) {
            continue;
        }
        let mut entry = item(format!("移到{}", target.name()), format!("move:{}", target as i32), true);
        if target == Place::Ball && ball_full {
            entry.enabled = false;
            entry.note = "没有空位".into();
        }
        items.push(entry);
    }
    items.push(item("换成别的…", "replace", true));
    if token.starts_with("button:") {
        items.push(item("改映射（去「映射」页）", "map", true));
    }
    items.push(WbItem { danger: true, ..item("拿掉", "remove", true) });
    Some(items)
}

/// 按钮库：最上面是「新建」，下面是能放进这一处的，分组列出（快捷发送只放按键）。放在别处的写「在：…」，选了就搬过来。
/// index：要换掉的那一个（悬浮球的空格也算），None 是后面的「＋」
fn library_items(config: &Config, slots: &Slots, place: Place, index: Option<usize>) -> Vec<WbItem> {
    let option = |token: String| {
        // 基础键可以各排各放一个：只看这一处有没有
        let here = if token.starts_with("key:") {
            slots.list(place).iter().position(|t| t.as_deref() == Some(token.as_str())).map(|i| (place, i))
        } else {
            slots.find(&token)
        };
        let (note, enabled) = match here {
            Some((p, i)) if p == place && Some(i) == index => ("就是这个".to_string(), false),
            Some((p, _)) => (format!("在：{}", p.name()), true),
            None => (String::new(), true),
        };
        WbItem { note: note.into(), ..item(wb::label(config, &token), format!("pick:{token}"), enabled) }
    };
    let buttons = config.layout.pages.iter().flat_map(|p| &p.modules).filter(|m| m.kind == ModuleKind::Button);
    let (texts, hotkeys): (Vec<&Module>, Vec<&Module>) = buttons.partition(|m| key_editor::editable_text(m).is_some());
    let button_items = |modules: &[&Module]| -> Vec<WbItem> { modules.iter().map(|m| option(format!("button:{}", m.id))).collect() };
    // 在这里直接新建：建成正式版按键（放在工作台专用页），放到点开按钮库的这一格
    let mut items = vec![header("新建"), item("＋ 新建热键…", "new:hotkey", true), item("＋ 新建常用文本…", "new:text", true)];
    if place == Place::QuickText {
        group(&mut items, "常用文本", button_items(&texts));
        group(&mut items, "热键", button_items(&hotkeys));
        return items;
    }
    group(&mut items, "手机动作", wb::PLACEABLE_FUNCTIONS.iter().map(|f| option(format!("fn:{f}"))).collect());
    group(&mut items, "热键", button_items(&hotkeys));
    group(&mut items, "常用文本", button_items(&texts));
    group(&mut items, "基础键", wb::BASIC_KEYS.iter().map(|k| option(format!("key:{k}"))).collect());
    items
}

/// 菜单、按钮库里点了一项，在 slots 上改成什么样
#[derive(Debug, PartialEq)]
enum Outcome {
    /// 改了（还没检查规则）：撤销记录里的说明，要不要带「撤销」按钮提示一下
    Changed { label: String, toast: Option<String> },
    /// 改不了：提示原因
    Refused(String),
    /// 去映射页改这个按键（token）
    Map(String),
    /// 什么都不做（已经到头了之类）
    Nothing,
}

/// shown 是点开菜单那一刻那里放的（「＋」是空的），index 是它现在的位置（locate 找好的）
fn apply(config: &Config, slots: &mut Slots, place: Place, index: Option<usize>, shown: &str, action: &str) -> Outcome {
    let this = wb::label(config, shown);
    let done = |label: String, toast: Option<String>| Outcome::Changed { label, toast };
    if action == "map" {
        return Outcome::Map(shown.to_string());
    }
    if let Some(target) = action.strip_prefix("move:") {
        let (Some(i), Some(target)) = (index, target.parse().ok().and_then(Place::from_index)) else { return Outcome::Nothing };
        return match slots.move_to(place, i, target) {
            Ok(()) => done(format!("把「{this}」移到{}", target.name()), Some(format!("「{this}」移到了{}", target.name()))),
            Err(reason) => Outcome::Refused(reason),
        };
    }
    if let Some(token) = action.strip_prefix("pick:") {
        let picked = wb::label(config, token);
        let from = slots.find(token).filter(|(p, _)| *p != place);
        return match slots.pick(token, place, index) {
            Ok(()) => {
                let label = if shown.is_empty() { format!("把「{picked}」放进{}", place.name()) } else { format!("把「{this}」换成「{picked}」") };
                done(label, from.map(|(p, _)| format!("「{picked}」从{}搬到了{}", p.name(), place.name())))
            }
            Err(reason) => Outcome::Refused(reason),
        };
    }
    let Some(i) = index else { return Outcome::Nothing };
    match action {
        "earlier" if i > 0 && slots.swap(place, i, i - 1) => done(format!("把「{this}」往前挪"), None),
        "later" if slots.swap(place, i, i + 1) => done(format!("把「{this}」往后挪"), None),
        "remove" if slots.take(place, i).is_some() => {
            done(format!("从{}拿掉「{this}」", place.name()), Some(format!("已从{}拿掉「{this}」", place.name())))
        }
        _ => Outcome::Nothing,
    }
}

// ───────── 拖着排顺序 ─────────

/// 改过的五处装回去（Slots 只给了换一格、放到最后这些，拖动要插在中间，就整排换掉）
fn with_lists(slots: &Slots, lists: &[Vec<Option<String>>]) -> Option<Slots> {
    let mut layout = slots.to_layout();
    for place in Place::ALL {
        let list: Vec<Value> = lists[place as usize].iter().map(|t| t.clone().map_or(Value::Null, Value::String)).collect();
        layout["slots"][place.key()] = Value::Array(list);
    }
    Slots::from_layout(&layout)
}

/// 拖着一个按钮松手：from 是它原来在哪（拖起来那一刻那里是 token），放到 to 这一处的 at：
/// 悬浮球是第几格，各排是插在第几个前面；None 是没压在哪个按钮上（悬浮球放进第一个空格，各排放到最后）。
/// 规则和手机一样：
/// - 同一排里挪位置；悬浮球里两格互换（语音、键鼠、精密鼠标也能换位置）；
/// - 搬到别处：原来那处空出来（悬浮球那格变空，排里去掉）；放到悬浮球有东西的那格上，两个互换；
/// - 固定的不能离开悬浮球，快捷发送只放正式版按键，基础键同一排不能有两个：这几条先说清楚原因，
///   一排最多 24 个这些交给 normalize。
///
/// 返回 Ok(None)：放回原处，不用改
fn drop_slots(config: &Config, slots: &Slots, from: (Place, usize), token: &str, to: Place, at: Option<usize>) -> Result<Option<Slots>, String> {
    let mut lists: Vec<Vec<Option<String>>> = Place::ALL.iter().map(|&p| slots.list(p).to_vec()).collect();
    let (from_place, from_index) = from;
    let source = from_place as usize;
    let target = to as usize;
    let same = |t: &Option<String>| t.as_deref() == Some(token);
    // 拖起来以后被别处挪过：跟着它在这一处的新位置走，不在这一处了就不动
    let from_index = if lists[source].get(from_index).is_some_and(same) {
        from_index
    } else {
        lists[source].iter().position(same).ok_or("工作台刚在别处改过了，再拖一次")?
    };
    let name = wb::label(config, token);
    if to != Place::Ball && fixed(token) {
        return Err(format!("「{name}」只能在悬浮球里挪位置"));
    }
    if to == Place::QuickText && !token.starts_with("button:") {
        return Err("快捷发送里只能放电脑上的按键（常用文本、热键）".into());
    }
    if to == Place::Ball {
        let slot = match at {
            Some(slot) if slot < lists[target].len() => slot,
            Some(_) => return Ok(None),
            None if from_place == Place::Ball => return Ok(None),
            None => lists[target].iter().position(Option::is_none).ok_or("悬浮球 8 个位置满了：拖到某一格上就是两个换位置")?,
        };
        if from_place == Place::Ball {
            if slot == from_index {
                return Ok(None);
            }
            lists[target].swap(from_index, slot);
        } else {
            let occupant = lists[target][slot].take();
            if let Some(o) = occupant.as_deref().filter(|o| fixed(o)) {
                return Err(format!("「{}」固定在悬浮球里，换不出去", wb::label(config, o)));
            }
            lists[target][slot] = Some(token.to_string());
            match occupant {
                // 被换下来的去拖走的那个原来的位置
                Some(o) => lists[source][from_index] = Some(o),
                None => {
                    lists[source].remove(from_index);
                }
            }
        }
    } else {
        let len = lists[target].len();
        let mut at = at.unwrap_or(len).min(len);
        if from_place == to {
            if at == from_index || at == from_index + 1 {
                return Ok(None);
            }
            let moved = lists[target].remove(from_index);
            if at > from_index {
                at -= 1;
            }
            lists[target].insert(at, moved);
        } else {
            if lists[target].iter().any(same) {
                return Err(format!("{}里已经有「{name}」了", to.name()));
            }
            if from_place == Place::Ball {
                lists[source][from_index] = None;
            } else {
                lists[source].remove(from_index);
            }
            lists[target].insert(at, Some(token.to_string()));
        }
    }
    with_lists(slots, &lists).map(Some).ok_or_else(|| "这样放不了".to_string())
}

/// 拖到 to 这一处的 at 松手会怎样：Ok(None) 放回原处；Ok(Some) 改好、也过了 normalize 的布局；Err 放不了的原因
fn drop_result(config: &Config, from: (Place, usize), token: &str, to: Place, at: Option<usize>) -> Result<Option<Slots>, String> {
    let (slots, ids) = current(config).ok_or("工作台布局读不出来：先点「生成默认布局」")?;
    let Some(next) = drop_slots(config, &slots, from, token, to, at)? else { return Ok(None) };
    wb::normalize(&next.to_layout(), &ids)?;
    Ok(Some(next))
}

/// 改好的工作台变成新配置：base 是改之前的配置（新建按键时已经建好了按键），slots 是改好的样子。
/// normalize 不过返回原因；过了就写进去，再删掉专用页上哪儿都没用到的按键（K2：拿掉了一个热键，它就没人用了。
/// 和这一步是同一步撤销，撤销时一起回来）
fn finish_edit(mut base: Config, slots: &Slots) -> Result<Config, String> {
    let ids = wb::official_button_ids(&base);
    let valid = wb::normalize(&slots.to_layout(), &ids)?;
    base.extra.insert(wb::KEY.into(), valid);
    wb::collect_unused_storage_buttons(&mut base);
    Ok(base)
}

// ───────── 新建热键、常用文本 ─────────

/// 「＋ 新建热键」「＋ 新建常用文本」要建的按键
#[derive(Clone, Debug)]
enum NewButton {
    /// keys 是空的：点了「去映射页细调」又没填按键，建一个单击还空着的按键，到映射页再配
    Hotkey { name: String, keys: Vec<String>, confirm: bool },
    Text { name: String, text: String, enter: bool },
}

impl NewButton {
    fn name(&self) -> &str {
        match self {
            NewButton::Hotkey { name, .. } | NewButton::Text { name, .. } => name,
        }
    }

    fn kind(&self) -> &'static str {
        match self {
            NewButton::Hotkey { .. } => "热键",
            NewButton::Text { .. } => "常用文本",
        }
    }
}

fn module_mut<'a>(config: &'a mut Config, id: &str) -> Option<&'a mut Module> {
    config.layout.pages.iter_mut().flat_map(|p| &mut p.modules).find(|m| m.id == id)
}

/// 工作台专用页在 pages 里是第几个
fn storage_index(config: &Config) -> Option<usize> {
    config.layout.pages.iter().position(wb::is_storage_page)
}

/// 专用页上还放不放得下一个 1 × 1 的按键（create_key_button 找的就是这么大的空位）
fn storage_has_room(config: &Config) -> bool {
    let Some(page) = storage_index(config) else { return true };
    let (columns, rows) = config.grid();
    let steps = |total: f64| ((total - 1.0) / geometry::SNAP + 1e-6).floor() as i64;
    (0..=steps(rows)).any(|yi| {
        (0..=steps(columns)).any(|xi| config.fits(page, None, Rect::new(xi as f64 * geometry::SNAP, yi as f64 * geometry::SNAP, 1.0, 1.0)))
    })
}

/// 专用页上的模块重新排一遍：这一页不显示，位置没人看，只要都在网格里、互不重叠就行。
/// 按原来的先后从左上角一个挨一个排，先试 1 × 1，排不下再试最小的 0.5 × 0.5。
/// room：排完还要放得下一个 1 × 1（马上要新建按键）。怎么排都不行返回 false，什么都不改
pub(crate) fn pack_storage(config: &mut Config, room: bool) -> bool {
    let Some(page) = storage_index(config) else { return true };
    let (columns, rows) = config.grid();
    let original = config.layout.pages[page].modules.clone();
    let mut order: Vec<usize> = (0..original.len()).collect();
    order.sort_by(|&a, &b| (original[a].y, original[a].x).partial_cmp(&(original[b].y, original[b].x)).unwrap_or(std::cmp::Ordering::Equal));
    for size in [1.0, MIN_SIZE] {
        let per_row = ((columns + geometry::EPS) / size).floor() as usize;
        let lines = ((rows + geometry::EPS) / size).floor() as usize;
        if per_row == 0 || order.len() > per_row * lines {
            continue;
        }
        let modules = &mut config.layout.pages[page].modules;
        for (n, &i) in order.iter().enumerate() {
            modules[i].set_rect(Rect::new((n % per_row) as f64 * size, (n / per_row) as f64 * size, size, size));
        }
        if !room || storage_has_room(config) {
            return true;
        }
    }
    config.layout.pages[page].modules = original;
    false
}

/// 改了网格以后专用页上有模块出了网格：重新排一遍（见 pack_storage）。排不下返回 false
pub(crate) fn fit_storage_to_grid(config: &mut Config) -> bool {
    let Some(page) = storage_index(config) else { return true };
    let (columns, rows) = config.grid();
    if config.layout.pages[page].modules.iter().all(|m| m.rect().inside(columns, rows)) {
        return true;
    }
    pack_storage(config, false)
}

/// 专用页上的模块在 columns × rows 的网格里排得下吗（按最小的 0.5 × 0.5 排）
pub(crate) fn storage_fits(config: &Config, columns: f64, rows: f64) -> bool {
    let count = storage_index(config).map_or(0, |p| config.layout.pages[p].modules.len());
    let per = |total: f64| ((total + geometry::EPS) / MIN_SIZE).floor() as usize;
    count <= per(columns) * per(rows)
}

/// 在工作台专用页上建这个正式版按键（没有专用页就先建上），返回 id。用的是和手机上新建时一样的
/// create_key_button / create_text_button（常用文本「打完按回车」是粘贴、等 30 毫秒、回车，见 K5）。
/// 专用页满了：先把上面的模块排紧一点腾出地方
fn create_on_storage(config: &mut Config, new: &NewButton) -> Result<String, String> {
    if storage_index(config).is_none() {
        config.layout.pages.push(Page { id: wb::SHORTCUT_PAGE.into(), name: wb::SHORTCUT_PAGE_NAME.into(), modules: Vec::new() });
    }
    if !storage_has_room(config) && !pack_storage(config, true) {
        return Err("放工作台按键的地方满了：先从工作台拿掉几个不用的".into());
    }
    let id = match new {
        NewButton::Hotkey { name, keys, .. } if keys.is_empty() => {
            // 先照常建（名字、位置、各方案的映射都按正式的来），再把单击换成还没选的按键（映射页上标红「请选择按键」）
            let id = config.create_key_button(wb::SHORTCUT_PAGE, name, &["Enter".to_string()])?;
            let mapping = module_mut(config, &id).map(|m| {
                m.behaviors.click = vec![Step::new(Action::default_of_kind(0))];
                config::ButtonMapping::of(m)
            });
            if let Some(mapping) = mapping {
                for profile in &mut config.profiles {
                    profile.buttons.insert(id.clone(), mapping.clone());
                }
            }
            id
        }
        NewButton::Hotkey { name, keys, .. } => config.create_key_button(wb::SHORTCUT_PAGE, name, keys)?,
        NewButton::Text { name, text, enter } => config.create_text_button(wb::SHORTCUT_PAGE, name, text, *enter)?,
    };
    // 按之前先确认（手机上单击先问一句，比如 Alt+F4）
    if let (NewButton::Hotkey { confirm: true, .. }, Some(m)) = (new, module_mut(config, &id)) {
        m.extra.insert("confirm".into(), Value::Bool(true));
    }
    Ok(id)
}

/// 对话框里写建好放到哪
fn target_text(config: &Config, target: &Target) -> String {
    match target.index {
        None if target.place == Place::Ball => "建好放进悬浮球的第一个空格".into(),
        None => format!("建好放到「{}」最后", target.place.name()),
        Some(i) if target.shown.is_empty() => format!("建好放进悬浮球第 {} 格", i + 1),
        Some(_) => format!("建好换掉「{}」里的「{}」", target.place.name(), wb::label(config, &target.shown)),
    }
}

/// Ctrl、Alt、Shift、Win（含左右）
fn is_modifier(key: &str) -> bool {
    matches!(config::key_code(key), Some(0x10..=0x12 | 0xA0..=0xA5 | 0x5B | 0x5C))
}

/// 修饰键排的顺序：Ctrl、Alt、Shift、Win（和录按键时一样），普通键在最后
fn key_rank(key: &str) -> usize {
    match config::key_code(key) {
        Some(0x11 | 0xA2 | 0xA3) => 0,
        Some(0x12 | 0xA4 | 0xA5) => 1,
        Some(0x10 | 0xA0 | 0xA1) => 2,
        Some(0x5B | 0x5C) => 3,
        _ => 9,
    }
}

/// 「新建热键」按键那一栏填得对不对：空的不算错（还没填）；不对就说哪里不对（和映射页组合键的说法一样）
fn combo_problem(text: &str) -> String {
    let keys = split_shortcut(text);
    if keys.is_empty() {
        return String::new();
    }
    if let Some(bad) = keys.iter().find(|k| config::key_code(k).is_none()) {
        return format!("不认识「{bad}」：键名照「按键」下拉框里的写（Ctrl、Shift、A、F5……），用 + 连接");
    }
    key_editor::key_action(&keys).err().unwrap_or_default()
}

/// 组合键里的主键（最后一个不是修饰键的）在按键下拉框里叫什么；没有就是空的
fn combo_main(text: &str) -> String {
    let keys = split_shortcut(text);
    keys.iter().rev().find(|k| !is_modifier(k)).and_then(|k| key_entry(k)).map(|(_, _, name)| name.to_string()).unwrap_or_default()
}

/// 从下拉框选了一个键（在 key_list 里的位置）：选的是普通键就换掉原来的主键，Ctrl、Alt 这些修饰键留着；
/// 选的是修饰键就加上去（主键留着）。修饰键按 Ctrl、Alt、Shift、Win 排在前面
fn combo_pick(text: &str, index: i32) -> String {
    let list = key_list();
    let Some(&(code, _)) = usize::try_from(index).ok().and_then(|i| list.get(i)) else { return text.to_string() };
    let picked = config::key_code(code);
    let keep_main = is_modifier(code);
    let mut keys: Vec<String> =
        split_shortcut(text).into_iter().filter(|k| config::key_code(k) != picked && (keep_main || is_modifier(k))).collect();
    keys.push(code.to_string());
    keys.sort_by_key(|k| key_rank(k));
    keys.join("+")
}

impl Ctx {
    /// 点了「远程工作台」标签
    pub(crate) fn open_workbench(self: &Rc<Self>) {
        {
            let mut app = self.app.borrow_mut();
            app.workbench = true;
            app.selected = None;
        }
        self.refresh_layout();
    }

    /// 标签上的五处按钮（refresh_layout 里调）。没在看这个标签就不用算，切过来时会再刷
    pub(crate) fn refresh_workbench(&self) {
        let Some(ui) = self.ui() else { return };
        let open = self.app.borrow().workbench;
        ui.set_wb_open(open);
        if !open {
            return;
        }
        let lists: Option<Vec<Vec<WbChip>>> = {
            let app = self.app.borrow();
            current(&app.config).map(|(slots, _)| {
                Place::ALL.iter().map(|&place| slots.list(place).iter().map(|t| chip(&app.config, t.as_deref())).collect()).collect()
            })
        };
        ui.set_wb_ready(lists.is_some());
        for (i, model) in self.wb.places.iter().enumerate() {
            sync_rows(model, lists.as_ref().map(|l| l[i].clone()).unwrap_or_default());
        }
    }

    /// 还没有工作台布局（后台没生成过，或者坏得修不好）：按默认生成（缺的热键建在工作台专用页），可以撤销
    pub(crate) fn wb_create(self: &Rc<Self>) {
        let mut next = self.app.borrow().config.clone();
        wb::ensure(&mut next);
        if current(&next).is_none() {
            self.toast("生成不了默认的工作台布局");
            return;
        }
        // 这也算工作台改了一次：专用页上以前留下、没人用的按键一起清掉（K2）
        wb::collect_unused_storage_buttons(&mut next);
        self.checkpoint(None, "生成默认的工作台布局");
        self.app.borrow_mut().config = next;
        self.changed();
        self.toast_undo("已生成默认的工作台布局");
    }

    /// 点开一个按钮的菜单（点的那一刻才算，拿到的是最新的）
    pub(crate) fn wb_menu(self: &Rc<Self>, place: i32, index: i32) -> ModelRc<WbItem> {
        let items = {
            let app = self.app.borrow();
            current(&app.config).zip(Place::from_index(place)).zip(usize::try_from(index).ok())
                .and_then(|(((slots, _), place), index)| menu_items(&slots, place, index))
        };
        ModelRc::new(VecModel::from(items.unwrap_or_default()))
    }

    /// 点开按钮库（换成别的…、悬浮球的空格、「＋」）。index -1 是「＋」
    pub(crate) fn wb_library(self: &Rc<Self>, place: i32, index: i32) -> ModelRc<WbItem> {
        let items = {
            let app = self.app.borrow();
            current(&app.config).zip(Place::from_index(place))
                .map(|((slots, _), place)| library_items(&app.config, &slots, place, usize::try_from(index).ok()))
        };
        ModelRc::new(VecModel::from(items.unwrap_or_default()))
    }

    /// 菜单、按钮库里点了一项。shown 是点开那一刻那里放的是什么（「＋」是空的）：
    /// 这期间被别处改过，就照 shown 找它现在在哪（locate），不会改到别的按钮上
    pub(crate) fn wb_act(self: &Rc<Self>, place: i32, index: i32, shown: SharedString, action: SharedString) {
        // 弹出的菜单关掉以后键盘焦点还给主窗口（Ctrl+Z 撤销靠它）
        self.restore_focus();
        let Some(place) = Place::from_index(place) else { return };
        let target = Target { place, index: usize::try_from(index).ok(), shown: shown.to_string() };
        if let Some(kind) = action.strip_prefix("new:") {
            self.wb_open_new(kind, target);
            return;
        }
        let (outcome, slots) = {
            let app = self.app.borrow();
            let Some((mut slots, _)) = current(&app.config) else {
                drop(app);
                self.toast("工作台布局读不出来：先点「生成默认布局」");
                self.refresh_workbench();
                return;
            };
            let Some(index) = locate(&slots, &target) else {
                drop(app);
                self.toast("工作台刚在别处改过了，再点一次");
                self.refresh_workbench();
                return;
            };
            (apply(&app.config, &mut slots, place, index, &target.shown, &action), slots)
        };
        match outcome {
            Outcome::Changed { label, toast } => {
                self.wb_commit(None, &slots, &label, toast);
            }
            Outcome::Refused(reason) => self.toast(&reason),
            Outcome::Map(token) => self.wb_edit_mapping(&token),
            Outcome::Nothing => {}
        }
    }

    /// 改好的布局记成一步撤销、自动保存（见 finish_edit）：normalize 不过就提示原因、什么都不改，返回 false。
    /// next：已经建好新按键的配置（None 就从现在的配置改）
    fn wb_commit(self: &Rc<Self>, next: Option<Config>, slots: &Slots, label: &str, toast: Option<String>) -> bool {
        let base = next.unwrap_or_else(|| self.app.borrow().config.clone());
        let next = match finish_edit(base, slots) {
            Ok(next) => next,
            Err(reason) => {
                self.toast(&reason);
                return false;
            }
        };
        let (same, buttons_changed) = {
            let app = self.app.borrow();
            (config::to_text(&next) == config::to_text(&app.config), wb::official_button_ids(&next) != wb::official_button_ids(&app.config))
        };
        if same {
            return false;
        }
        self.checkpoint(None, label);
        self.app.borrow_mut().config = next;
        // 建了、删了按键：映射页、总览的列表也跟着变
        if buttons_changed {
            self.refresh_all();
        } else {
            self.refresh_workbench();
        }
        self.schedule_save();
        if let Some(message) = toast {
            self.toast_undo(&message);
        }
        true
    }

    /// 「改映射」：去映射页，选中这个按键（从单击开始）
    fn wb_edit_mapping(self: &Rc<Self>, token: &str) {
        let Some(id) = token.strip_prefix("button:") else { return };
        let found = {
            let app = self.app.borrow();
            let config = &app.config;
            button_refs(config).into_iter().enumerate()
                .find(|&(_, (pi, mi))| config.layout.pages[pi].modules[mi].id == id)
                .map(|(index, (pi, _))| (index, config.layout.pages[pi].id.clone()))
        };
        let Some((index, page)) = found else {
            self.toast("这个按键已经不在了");
            return;
        };
        // 映射页列表里这一页收起来了就展开，免得选中了却看不到
        self.collapsed.borrow_mut().remove(&page);
        {
            let mut app = self.app.borrow_mut();
            app.map_index = index;
            app.map_trigger = Trigger::Click;
        }
        self.refresh_mapping();
        if let Some(ui) = self.ui() {
            ui.set_nav(2);
        }
    }

    // ───────── 拖着排顺序（界面上的 WbDrag） ─────────

    /// 拖动中：松手放到那里行不行（界面上画插入线、红色的提示用）。""：能放；"="：放回原处、不用改；别的：放不了的原因
    pub(crate) fn wb_drop_check(&self, from_place: i32, from_index: i32, token: SharedString, to_place: i32, to_index: i32) -> SharedString {
        let from = Place::from_index(from_place).zip(usize::try_from(from_index).ok());
        let (Some(from), Some(to)) = (from, Place::from_index(to_place)) else { return "=".into() };
        // 界面画图时随时会来问：正在改配置（借不到）就当放回原处
        let Ok(app) = self.app.try_borrow() else { return "=".into() };
        match drop_result(&app.config, from, &token, to, usize::try_from(to_index).ok()) {
            Ok(Some(_)) => "".into(),
            Ok(None) => "=".into(),
            Err(reason) => reason.into(),
        }
    }

    /// 拖着按钮松手：放得下就记一步撤销、保存；放不了提示原因
    pub(crate) fn wb_drop(self: &Rc<Self>, from_place: i32, from_index: i32, token: SharedString, to_place: i32, to_index: i32) {
        let from = Place::from_index(from_place).zip(usize::try_from(from_index).ok());
        let (Some(from), Some(to)) = (from, Place::from_index(to_place)) else { return };
        let result = drop_result(&self.app.borrow().config, from, &token, to, usize::try_from(to_index).ok());
        match result {
            Ok(None) => {}
            Err(reason) => self.toast(&reason),
            Ok(Some(slots)) => {
                let name = wb::label(&self.app.borrow().config, &token);
                let (label, toast) = if from.0 == to {
                    (format!("挪动「{name}」"), None)
                } else {
                    (format!("把「{name}」移到{}", to.name()), Some(format!("「{name}」移到了{}", to.name())))
                };
                self.wb_commit(None, &slots, &label, toast);
            }
        }
    }

    // ───────── 新建热键、常用文本 ─────────

    /// 按钮库里点了「＋ 新建热键」「＋ 新建常用文本」：记下建好放到哪，打开对话框
    fn wb_open_new(self: &Rc<Self>, kind: &str, target: Target) {
        let Some(ui) = self.ui() else { return };
        let text = target_text(&self.app.borrow().config, &target);
        *self.wb.pending.borrow_mut() = Some(target);
        ui.set_wb_new_where(text.into());
        if kind == "hotkey" {
            ui.set_wb_hotkey_combo("".into());
            ui.set_wb_hotkey_open(true);
        } else {
            ui.set_wb_text_open(true);
        }
    }

    /// 「新建热键」点了「新建」（map：点的是「去映射页细调」，建好跳到映射页选中它）
    pub(crate) fn wb_hotkey_save(self: &Rc<Self>, name: SharedString, combo: SharedString, confirm: bool, map: bool) {
        let keys = split_shortcut(&combo);
        if keys.is_empty() && !map {
            self.toast("先填按键：点「录制」按一下，或者照「按键」下拉框里的写，比如 Ctrl+S");
            return;
        }
        let problem = combo_problem(&combo);
        if !problem.is_empty() {
            self.toast(&problem);
            return;
        }
        let name = match name.trim() {
            "" if keys.is_empty() => "新热键".to_string(),
            "" => keys.join("+"),
            name => name.to_string(),
        };
        self.wb_create_button(NewButton::Hotkey { name, keys, confirm }, map);
    }

    /// 「新建常用文本」点了「新建」
    pub(crate) fn wb_text_save(self: &Rc<Self>, name: SharedString, text: SharedString, enter: bool) {
        let name = name.trim().to_string();
        if name.is_empty() {
            self.toast("先起个名字（按钮上显示它）");
            return;
        }
        self.wb_create_button(NewButton::Text { name, text: text.to_string(), enter }, false);
    }

    /// 建按键、放进点开按钮库的那一格，一起记成一步撤销（K2 的清理也在这一步里）。建不了就提示原因，对话框留着
    fn wb_create_button(self: &Rc<Self>, new: NewButton, map: bool) {
        let Some(target) = self.wb.pending.borrow().clone() else {
            self.wb_new_cancel();
            return;
        };
        let mut next = self.app.borrow().config.clone();
        let Some((mut slots, _)) = current(&next) else {
            self.toast("工作台布局读不出来：先点「生成默认布局」");
            return;
        };
        // 点开按钮库的那一格这期间变了（手机上刚调过布局）：放到这一处的最后
        let index = locate(&slots, &target).unwrap_or(None);
        let id = match create_on_storage(&mut next, &new) {
            Ok(id) => id,
            Err(reason) => {
                self.toast(&reason);
                return;
            }
        };
        let token = format!("button:{id}");
        if let Err(reason) = slots.put(&token, target.place, index, None) {
            self.toast(&reason);
            return;
        }
        let (kind, name) = (new.kind(), new.name().to_string());
        if !self.wb_commit(Some(next), &slots, &format!("新建{kind}「{name}」"), None) {
            return;
        }
        self.wb.pending.take();
        if let Some(ui) = self.ui() {
            ui.set_wb_hotkey_open(false);
            ui.set_wb_text_open(false);
        }
        self.restore_focus();
        if map {
            self.wb_edit_mapping(&token);
            self.toast_undo(&format!("已新建{kind}「{name}」，在这里配它的动作"));
        } else {
            self.toast_undo(&format!("已新建{kind}「{name}」，放进了{}", target.place.name()));
        }
    }

    /// 对话框点了取消、按了 Esc
    pub(crate) fn wb_new_cancel(self: &Rc<Self>) {
        self.wb.pending.take();
        if let Some(ui) = self.ui() {
            ui.set_wb_hotkey_open(false);
            ui.set_wb_text_open(false);
            ui.global::<Recorder>().set_active(false);
        }
        self.restore_focus();
    }

    /// 「新建热键」里录到的键（tools.rs 的 record_key 录好以后交过来）：填进按键那一栏，点「新建」才建
    pub(crate) fn wb_recorded(&self, keys: Vec<String>) {
        if let Some(ui) = self.ui() {
            ui.set_wb_hotkey_combo(keys.join("+").into());
        }
    }

    pub(crate) fn wb_combo_problem(&self, text: SharedString) -> SharedString {
        combo_problem(&text).into()
    }

    pub(crate) fn wb_combo_main(&self, text: SharedString) -> SharedString {
        combo_main(&text).into()
    }

    pub(crate) fn wb_combo_pick(&self, text: SharedString, index: i32) -> SharedString {
        combo_pick(&text, index).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 默认的工作台，外加两条常用文本（一条放进了快捷发送）
    fn setup() -> (Config, Slots, HashSet<String>, String, String) {
        let mut config = Config::fresh();
        wb::ensure(&mut config);
        let placed = config.create_text_button(wb::SHORTCUT_PAGE, "收到", "收到", false).unwrap();
        let loose = config.create_text_button(wb::SHORTCUT_PAGE, "发出去了", "发出去了", true).unwrap();
        assert!(wb::append_quick_text(&mut config, &placed));
        let (slots, ids) = current(&config).unwrap();
        (config, slots, ids, placed, loose)
    }

    fn actions(items: &[WbItem]) -> Vec<String> {
        items.iter().filter(|i| !i.header).map(|i| i.action.to_string()).collect()
    }

    fn tokens(slots: &Slots, place: Place) -> Vec<String> {
        slots.list(place).iter().map(|t| t.clone().unwrap_or_default()).collect()
    }

    fn token_of(config: &Config, label: &str) -> String {
        let m = config.layout.pages.iter().flat_map(|p| &p.modules).find(|m| m.label == label).unwrap();
        format!("button:{}", m.id)
    }

    #[test]
    fn fixed_chips_only_move() {
        let (_, slots, ..) = setup();
        let items = menu_items(&slots, Place::Ball, 0).unwrap();
        assert_eq!(actions(&items), ["earlier", "later"]);
        assert!(!items[0].enabled && items[1].enabled);
        assert!(menu_items(&slots, Place::Ball, 9).is_none());
    }

    #[test]
    fn menus_offer_allowed_places_only() {
        let (config, slots, _, placed, _) = setup();
        // 悬浮球里的 Alt+F4（正式版按键）：能去别的四处，能改映射
        let f4 = slots.list(Place::Ball)[5].clone().unwrap();
        assert!(f4.starts_with("button:"));
        assert_eq!(chip(&config, Some(&f4)).detail.as_str(), "正式版按键 · 按之前先确认");
        let items = menu_items(&slots, Place::Ball, 5).unwrap();
        assert_eq!(actions(&items), ["earlier", "move:1", "move:2", "move:3", "move:4", "replace", "map", "remove"]);
        assert!(items.last().unwrap().danger);
        // 键鼠条里的系统截图（手机动作）：不能去快捷发送；悬浮球满了灰掉
        let km = slots.list(Place::Keymouse);
        let capture = km.iter().position(|t| t.as_deref() == Some("fn:captureSystem")).unwrap();
        let items = menu_items(&slots, Place::Keymouse, capture).unwrap();
        assert_eq!(actions(&items), ["earlier", "move:0", "move:2", "move:4", "replace", "remove"]);
        assert!(!items[1].enabled);
        assert_eq!(items[1].note.as_str(), "没有空位");
        assert_eq!(chip(&config, Some(&format!("button:{placed}"))).detail.as_str(), "常用文本");
        assert!(!chip(&config, Some(&format!("button:{placed}"))).enter);
    }

    #[test]
    fn library_groups_and_notes() {
        let (config, slots, _, placed, loose) = setup();
        // 最上面都是「新建」
        let items = library_items(&config, &slots, Place::QuickText, None);
        assert_eq!(items[0].text.as_str(), "新建");
        assert_eq!(actions(&items[..3]), ["new:hotkey", "new:text"]);
        // 快捷发送只列按键，常用文本在前
        assert_eq!(items[3].text.as_str(), "常用文本");
        assert!(items[3..].iter().filter(|i| !i.header).all(|i| i.action.starts_with("pick:button:")));
        let mine = items.iter().find(|i| i.action.as_str() == format!("pick:button:{placed}")).unwrap();
        assert_eq!(mine.note.as_str(), "在：语音条 · 快捷发送");
        let other = items.iter().find(|i| i.action.as_str() == format!("pick:button:{loose}")).unwrap();
        assert_eq!((other.text.as_str(), other.note.as_str(), other.enabled), ("发出去了", "", true));
        let loose_chip = chip(&config, Some(&format!("button:{loose}")));
        assert!(loose_chip.enter && loose_chip.detail.as_str() == "常用文本 · 打完按回车");
        // 悬浮球第 6 格换成别的：它自己灰掉，别处的写在哪，固定的不列
        let items = library_items(&config, &slots, Place::Ball, Some(5));
        let headers: Vec<&str> = items.iter().filter(|i| i.header).map(|i| i.text.as_str()).collect();
        assert_eq!(headers, ["新建", "手机动作", "热键", "常用文本", "基础键"]);
        let f4 = slots.list(Place::Ball)[5].clone().unwrap();
        let me = items.iter().find(|i| i.action.as_str() == format!("pick:{f4}")).unwrap();
        assert_eq!((me.note.as_str(), me.enabled), ("就是这个", false));
        let send = items.iter().find(|i| i.action.as_str() == "pick:fn:sendFile").unwrap();
        assert_eq!((send.text.as_str(), send.note.as_str()), ("发图片", "在：悬浮球"));
        assert!(!items.iter().any(|i| i.action.as_str() == "pick:fn:voice"));
        let backspace = items.iter().find(|i| i.action.as_str() == "pick:key:backspace").unwrap();
        assert_eq!((backspace.text.as_str(), backspace.note.as_str()), ("退格", ""));
        // 键鼠条后面的「＋」：这一排已有的基础键写着在哪
        let items = library_items(&config, &slots, Place::Keymouse, None);
        let backspace = items.iter().find(|i| i.action.as_str() == "pick:key:backspace").unwrap();
        assert_eq!(backspace.note.as_str(), "在：键鼠条");
    }

    #[test]
    fn actions_change_slots_and_pass_normalize() {
        let (config, slots, ids, placed, loose) = setup();
        let check = |s: &Slots| wb::normalize(&s.to_layout(), &ids);
        // 拿掉：悬浮球那格变空
        let mut s = slots.clone();
        let f4 = s.list(Place::Ball)[5].clone().unwrap();
        let outcome = apply(&config, &mut s, Place::Ball, Some(5), &f4, "remove");
        assert!(matches!(&outcome, Outcome::Changed { toast: Some(t), .. } if t == "已从悬浮球拿掉「Alt+F4」"));
        assert_eq!(s.list(Place::Ball)[5], None);
        assert!(check(&s).is_ok());
        // 从按钮库挑别处的常用文本放进侧栏：从快捷发送搬过来
        let mut s = slots.clone();
        let token = format!("button:{placed}");
        let outcome = apply(&config, &mut s, Place::Rail, None, "", &format!("pick:{token}"));
        assert_eq!(outcome, Outcome::Changed {
            label: "把「收到」放进侧栏".into(),
            toast: Some("「收到」从语音条 · 快捷发送搬到了侧栏".into()),
        });
        assert_eq!(s.list(Place::Rail), &[Some(token.clone())]);
        assert!(s.list(Place::QuickText).is_empty());
        assert!(check(&s).is_ok());
        // 往快捷发送放手机动作：slots 里放了，但 normalize 不让（界面上提示原因、不保存）
        let mut s = slots.clone();
        assert!(matches!(apply(&config, &mut s, Place::QuickText, None, "", "pick:fn:sendFile"), Outcome::Changed { .. }));
        assert!(check(&s).is_err());
        // 换成别的：快捷发送里那条换成另一条
        let mut s = slots.clone();
        let outcome = apply(&config, &mut s, Place::QuickText, Some(0), &token, &format!("pick:button:{loose}"));
        assert!(matches!(&outcome, Outcome::Changed { label, toast: None } if label == "把「收到」换成「发出去了」"));
        assert_eq!(s.list(Place::QuickText), &[Some(format!("button:{loose}"))]);
        // 往前挪、往后挪、移到；到头了什么都不做
        let mut s = slots.clone();
        assert_eq!(apply(&config, &mut s, Place::Ball, Some(0), "fn:voice", "earlier"), Outcome::Nothing);
        assert!(matches!(apply(&config, &mut s, Place::Ball, Some(0), "fn:voice", "later"), Outcome::Changed { .. }));
        assert_eq!(s.list(Place::Ball)[1].as_deref(), Some("fn:voice"));
        assert!(matches!(apply(&config, &mut s, Place::VoiceRow, Some(0), "key:backspace", "move:4"), Outcome::Changed { .. }));
        assert_eq!(s.list(Place::Rail), &[Some("key:backspace".to_string())]);
        assert!(check(&s).is_ok());
        // 悬浮球满了还往里搬：提示原因
        let mut s = slots.clone();
        assert!(matches!(apply(&config, &mut s, Place::Rail, None, "", "pick:key:esc"), Outcome::Changed { .. }));
        assert!(matches!(apply(&config, &mut s, Place::Rail, Some(0), "key:esc", "move:0"), Outcome::Refused(_)));
        // 改映射：交给映射页
        assert_eq!(apply(&config, &mut slots.clone(), Place::Ball, Some(5), &f4, "map"), Outcome::Map(f4.clone()));
    }

    /// 删了被引用的按键、还没保存：标签上照样显示，只是那一格空了
    #[test]
    fn deleted_buttons_vanish_before_saving() {
        let (mut config, ..) = setup();
        let f4 = current(&config).unwrap().0.list(Place::Ball)[5].clone().unwrap();
        let id = f4.strip_prefix("button:").unwrap().to_string();
        for page in &mut config.layout.pages {
            page.modules.retain(|m| m.id != id);
        }
        let (slots, _) = current(&config).unwrap();
        assert_eq!(slots.list(Place::Ball)[5], None);
        config.extra.remove(wb::KEY);
        assert!(current(&config).is_none());
    }

    /// 审查 #6：菜单点开以后别处改了，按点开时记下的那个找它现在在哪
    #[test]
    fn menu_follows_the_item_it_was_opened_on() {
        let (config, slots, _, placed, loose) = setup();
        let km = tokens(&slots, Place::Keymouse);
        let target = Target { place: Place::Keymouse, index: Some(1), shown: km[1].clone() };
        assert_eq!(locate(&slots, &target), Some(Some(1)));
        // 前面那个被拿掉了：跟着挪到第 0 个，不会改到原来第 2 个上
        let mut s = slots.clone();
        s.take(Place::Keymouse, 0);
        assert_eq!(locate(&s, &target), Some(Some(0)));
        let now = locate(&s, &target).unwrap();
        let outcome = apply(&config, &mut s, Place::Keymouse, now, &target.shown, "remove");
        assert!(matches!(outcome, Outcome::Changed { .. }));
        assert!(!tokens(&s, Place::Keymouse).contains(&km[1]));
        assert!(tokens(&s, Place::Keymouse).contains(&km[2]));
        // 它整个不在这一处了：不动
        let mut s = slots.clone();
        s.take(Place::Keymouse, 1);
        assert_eq!(locate(&s, &target), None);
        // 悬浮球的空格被别处填上了：不动；「＋」不用找
        let mut s = slots.clone();
        s.take(Place::Ball, 6);
        let empty = Target { place: Place::Ball, index: Some(6), shown: String::new() };
        assert_eq!(locate(&s, &empty), Some(Some(6)));
        s.pick(&format!("button:{loose}"), Place::Ball, Some(6)).unwrap();
        assert_eq!(locate(&s, &empty), None);
        let plus = Target { place: Place::QuickText, index: None, shown: String::new() };
        assert_eq!(locate(&s, &plus), Some(None));
        assert_eq!(target_text(&config, &plus), "建好放到「语音条 · 快捷发送」最后");
        let replace = Target { place: Place::QuickText, index: Some(0), shown: format!("button:{placed}") };
        assert_eq!(target_text(&config, &replace), "建好换掉「语音条 · 快捷发送」里的「收到」");
    }

    #[test]
    fn drag_reorders_within_a_row_and_swaps_in_the_ball() {
        let (config, slots, ..) = setup();
        let voice = tokens(&slots, Place::VoiceRow);
        // 第 0 个拖到第 3 个前面：落在原来第 2 个后面
        let s = drop_result(&config, (Place::VoiceRow, 0), &voice[0], Place::VoiceRow, Some(3)).unwrap().unwrap();
        assert_eq!(tokens(&s, Place::VoiceRow)[..3], [voice[1].clone(), voice[2].clone(), voice[0].clone()]);
        // 往前拖到最前；拖到最后（没压在按钮上）
        let s = drop_result(&config, (Place::VoiceRow, 4), &voice[4], Place::VoiceRow, Some(0)).unwrap().unwrap();
        assert_eq!(tokens(&s, Place::VoiceRow)[0], voice[4]);
        let s = drop_result(&config, (Place::VoiceRow, 0), &voice[0], Place::VoiceRow, None).unwrap().unwrap();
        assert_eq!(tokens(&s, Place::VoiceRow).last(), Some(&voice[0]));
        // 放回自己前后：不用改
        assert_eq!(drop_result(&config, (Place::VoiceRow, 2), &voice[2], Place::VoiceRow, Some(2)), Ok(None));
        assert_eq!(drop_result(&config, (Place::VoiceRow, 2), &voice[2], Place::VoiceRow, Some(3)), Ok(None));
        // 悬浮球：两格互换，固定的也能换位置；空格就是挪过去
        let s = drop_result(&config, (Place::Ball, 0), "fn:voice", Place::Ball, Some(4)).unwrap().unwrap();
        assert_eq!(s.list(Place::Ball)[4].as_deref(), Some("fn:voice"));
        assert_eq!(s.list(Place::Ball)[0].as_deref(), Some("fn:captureWechat"));
        let mut emptied = config.clone();
        let mut layout = emptied.extra[wb::KEY].clone();
        layout["slots"]["ball"][6] = Value::Null;
        emptied.extra.insert(wb::KEY.into(), layout);
        let s = drop_result(&emptied, (Place::Ball, 3), "fn:taskSwitch", Place::Ball, Some(6)).unwrap().unwrap();
        assert_eq!((s.list(Place::Ball)[3].clone(), s.list(Place::Ball)[6].as_deref()), (None, Some("fn:taskSwitch")));
        assert_eq!(drop_result(&config, (Place::Ball, 3), "fn:taskSwitch", Place::Ball, None), Ok(None));
    }

    #[test]
    fn drag_between_rows_follows_the_phone_rules() {
        let (config, slots, _, placed, _) = setup();
        let text = format!("button:{placed}");
        let copy = token_of(&config, "复制");
        // 键鼠条的「复制」拖进快捷发送最前：键鼠条里去掉
        let s = drop_result(&config, (Place::Keymouse, 7), &copy, Place::QuickText, Some(0)).unwrap().unwrap();
        assert_eq!(tokens(&s, Place::QuickText), [copy.clone(), text.clone()]);
        assert!(!tokens(&s, Place::Keymouse).contains(&copy));
        // 手机动作、基础键进不了快捷发送；固定的出不了悬浮球
        let reason = drop_result(&config, (Place::Keymouse, 10), "fn:captureSystem", Place::QuickText, None).unwrap_err();
        assert_eq!(reason, "快捷发送里只能放电脑上的按键（常用文本、热键）");
        assert_eq!(drop_result(&config, (Place::Ball, 1), "fn:keymouse", Place::Rail, None).unwrap_err(), "「键鼠」只能在悬浮球里挪位置");
        // 基础键：别的排没有就能搬过去（原来那排去掉），这一排已经有了就不行
        let s = drop_result(&config, (Place::VoiceRow, 6), "key:capslock", Place::Keymouse, Some(1)).unwrap().unwrap();
        assert_eq!(tokens(&s, Place::Keymouse)[1], "key:capslock");
        assert!(!tokens(&s, Place::VoiceRow).contains(&"key:capslock".to_string()));
        assert_eq!(drop_result(&config, (Place::VoiceRow, 0), "key:backspace", Place::Keymouse, None).unwrap_err(), "键鼠条里已经有「退格」了");
        // 从排里拖到悬浮球有东西的格子上：两个互换（被换下的去原来那个的位置）；固定的那格换不走
        let f4 = slots.list(Place::Ball)[5].clone().unwrap();
        let s = drop_result(&config, (Place::QuickText, 0), &text, Place::Ball, Some(5)).unwrap().unwrap();
        assert_eq!(s.list(Place::Ball)[5].as_deref(), Some(text.as_str()));
        assert_eq!(tokens(&s, Place::QuickText), [f4.clone()]);
        assert_eq!(drop_result(&config, (Place::QuickText, 0), &text, Place::Ball, Some(2)).unwrap_err(), "「精密鼠标」固定在悬浮球里，换不出去");
        // 换下来的要是放不进那一排（手机动作进快捷发送），normalize 不让
        assert!(drop_result(&config, (Place::QuickText, 0), &text, Place::Ball, Some(3)).is_err());
        // 悬浮球满了又没压在格子上：说原因；从悬浮球拖到排里：那一格空出来
        assert!(drop_result(&config, (Place::QuickText, 0), &text, Place::Ball, None).unwrap_err().starts_with("悬浮球 8 个位置满了"));
        let s = drop_result(&config, (Place::Ball, 5), &f4, Place::Rail, None).unwrap().unwrap();
        assert_eq!((s.list(Place::Ball)[5].clone(), tokens(&s, Place::Rail)), (None, vec![f4.clone()]));
        // 拖起来以后别处改过：按它找现在的位置；不在这一处了就不动
        let s = drop_result(&config, (Place::Keymouse, 0), &copy, Place::Rail, None).unwrap().unwrap();
        assert_eq!(tokens(&s, Place::Rail), [copy.clone()]);
        assert!(drop_result(&config, (Place::Rail, 0), &copy, Place::VoiceRow, None).is_err());
    }

    /// K2：拿掉专用页上的热键，它就没人用了，同一步里删掉（各方案的映射一起）；别的页上的按键不动
    #[test]
    fn removing_a_hotkey_collects_it_in_the_same_edit() {
        let (config, slots, _, _, loose) = setup();
        let f4 = slots.list(Place::Ball)[5].clone().unwrap();
        let id = f4.strip_prefix("button:").unwrap().to_string();
        let mut s = slots.clone();
        assert!(matches!(apply(&config, &mut s, Place::Ball, Some(5), &f4, "remove"), Outcome::Changed { .. }));
        let next = finish_edit(config.clone(), &s).unwrap();
        assert!(next.find_module(&id).is_none());
        assert!(next.profiles.iter().all(|p| !p.buttons.contains_key(&id)));
        assert!(next.find_module("esc").is_some());
        // 只是挪位置：放着的一个都不删；专用页上建了没放进工作台的（「发出去了」）没人用，也清掉
        let mut s = slots.clone();
        assert!(s.swap(Place::Ball, 5, 6));
        let next = finish_edit(config.clone(), &s).unwrap();
        let mut kept = wb::official_button_ids(&config);
        assert!(kept.remove(&loose));
        assert_eq!(wb::official_button_ids(&next), kept);
        // 不合格：说原因，什么都不改
        let mut s = slots.clone();
        s.take(Place::Ball, 0);
        assert!(finish_edit(config, &s).is_err());
    }

    #[test]
    fn new_hotkey_lands_on_the_storage_page_and_in_the_slot() {
        let (config, slots, ..) = setup();
        let mut next = config.clone();
        let new = NewButton::Hotkey { name: "保存".into(), keys: vec!["Ctrl".into(), "S".into()], confirm: true };
        let id = create_on_storage(&mut next, &new).unwrap();
        let storage = &next.layout.pages[storage_index(&next).unwrap()];
        let module = storage.modules.iter().find(|m| m.id == id).unwrap();
        assert_eq!(module.label, "保存");
        assert_eq!(module.extra.get("confirm"), Some(&Value::Bool(true)));
        assert_eq!(module.behaviors.click[0].action, Action::Shortcut { keys: vec!["Ctrl".into(), "S".into()] });
        // 换掉悬浮球里的 Alt+E：新的放进那一格，Alt+E 没人用了，同一步里删掉
        let alt_e = slots.list(Place::Ball)[6].clone().unwrap();
        let mut s = slots.clone();
        s.put(&format!("button:{id}"), Place::Ball, Some(6), None).unwrap();
        let done = finish_edit(next, &s).unwrap();
        assert_eq!(done.extra[wb::KEY]["slots"]["ball"][6], Value::String(format!("button:{id}")));
        assert!(done.find_module(alt_e.strip_prefix("button:").unwrap()).is_none());
        // 细调又没填按键：单击是还没选的按键，各方案都一样
        let mut next = config.clone();
        let blank = create_on_storage(&mut next, &NewButton::Hotkey { name: "新热键".into(), keys: vec![], confirm: false }).unwrap();
        let module = next.find_module(&blank).unwrap();
        assert_eq!(module.behaviors.click.len(), 1);
        assert_eq!(module.behaviors.click[0].action, Action::Key { key: String::new() });
        assert!(module.extra.get("confirm").is_none());
        assert!(next.profiles.iter().all(|p| p.buttons[&blank].click[0].action == Action::Key { key: String::new() }));
    }

    /// K5：新建的常用文本「打完按回车」是粘贴、等一下、回车，标签上照样认得出来
    #[test]
    fn new_text_uses_the_official_text_button() {
        let (config, slots, ..) = setup();
        let mut next = config.clone();
        let id = create_on_storage(&mut next, &NewButton::Text { name: "地址".into(), text: "北京市\n海淀区".into(), enter: true }).unwrap();
        let module = next.find_module(&id).unwrap();
        assert_eq!(key_editor::editable_text(module).map(|(_, text, enter)| (text, enter)), Some(("北京市\n海淀区", true)));
        assert_eq!(module.behaviors.click.len(), 3);
        let mut s = slots.clone();
        s.put(&format!("button:{id}"), Place::QuickText, None, None).unwrap();
        let done = finish_edit(next.clone(), &s).unwrap();
        assert_eq!(done.extra[wb::KEY]["slots"][wb::QUICK_TEXT].as_array().unwrap().last(), Some(&Value::String(format!("button:{id}"))));
        assert!(chip(&done, Some(&format!("button:{id}"))).enter);
        // 名字、文字不对：建不了，配置不动
        let mut bad = config.clone();
        assert!(create_on_storage(&mut bad, &NewButton::Text { name: "空".into(), text: "  ".into(), enter: false }).is_err());
        assert!(create_on_storage(&mut bad, &NewButton::Hotkey { name: "x".into(), keys: vec!["Ctrl".into(), "Nope".into()], confirm: false }).is_err());
        assert_eq!(wb::official_button_ids(&bad), wb::official_button_ids(&config));
    }

    /// 专用页满了：排紧一点（0.5 × 0.5）再建；网格改小了也能重新排进去
    #[test]
    fn storage_page_packs_when_full() {
        let mut config = Config::fresh();
        wb::ensure(&mut config);
        let page = storage_index(&config).unwrap();
        for i in 0..26 {
            config.create_text_button(wb::SHORTCUT_PAGE, &format!("文本{i}"), "好的", false).unwrap();
        }
        assert_eq!(config.layout.pages[page].modules.len(), 32);
        assert!(!storage_has_room(&config));
        let id = create_on_storage(&mut config, &NewButton::Text { name: "第 33 个".into(), text: "好".into(), enter: false }).unwrap();
        assert!(config.find_module(&id).is_some());
        assert!(config.check_layout().is_ok());
        assert!(config.layout.pages[page].modules.iter().filter(|m| m.id != id).all(|m| m.w == MIN_SIZE));
        // 网格改小：专用页上出了网格的重新排；多到排不下的网格不让改
        assert!(storage_fits(&config, 2.0, 5.0));
        assert!(!storage_fits(&config, 2.0, 3.0));
        config.layout.columns = 2;
        config.layout.rows = 5;
        assert!(fit_storage_to_grid(&mut config));
        let packed = &config.layout.pages[page].modules;
        assert_eq!(packed.len(), 33);
        assert!(packed.iter().all(|m| m.rect().inside(2.0, 5.0)));
        assert!(packed.iter().enumerate().all(|(i, m)| packed[i + 1..].iter().all(|o| !o.rect().overlaps(&m.rect()))));
    }

    #[test]
    fn combo_field_helpers() {
        assert_eq!(combo_problem(""), "");
        assert_eq!(combo_problem("Ctrl+Shift+P"), "");
        assert!(combo_problem("Ctrl+Nope").starts_with("不认识「Nope」"));
        assert_eq!(combo_problem("Ctrl+Ctrl"), "快捷键包含重复按键");
        assert_eq!(combo_main("Ctrl+Shift+Enter"), "回车");
        assert_eq!(combo_main("Ctrl"), "");
        let index = |code: &str| key_list().iter().position(|(c, _)| *c == code).unwrap() as i32;
        // 选主键：修饰键留着，原来的主键换掉
        assert_eq!(combo_pick("Ctrl+Shift+P", index("Tab")), "Ctrl+Shift+Tab");
        assert_eq!(combo_pick("", index("Esc")), "Esc");
        // 选的是修饰键：加上去（主键留着），按 Ctrl、Alt、Shift、Win 排
        assert_eq!(combo_pick("Shift+A", index("Ctrl")), "Ctrl+Shift+A");
        assert_eq!(combo_pick("Win+D", index("Alt")), "Alt+Win+D");
        assert_eq!(combo_pick("Ctrl+A", index("Ctrl")), "Ctrl+A");
        assert_eq!(combo_pick("Ctrl+S", -1), "Ctrl+S");
    }
}
