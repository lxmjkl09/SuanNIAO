//! Strict validation for remote workbench orientation placements (schema v2).
//!
//! Persist only appearance metadata and stable actions, never execute arbitrary
//! keyboard names or a remote script from UI-supplied layout JSON.
//! The previous "screenWorkbench" config remains untouched and readable by
//! all existing Android versions.

use serde_json::{Map, Number, Value};

const MAX_PLACEMENTS: usize = 120;
const MAX_ID: usize = 100;
const CONTAINERS: [&str; 4] = ["ball", "rail", "toolbar", "grid"];
const BUILTIN_ACTIONS: [&str; 12] = [
    "switch", "mouse", "capture", "voice", "rail",
    "input.keyboard", "shortcut.altF4", "shortcut.altE",
    "shortcut.ctrlA", "shortcut.ctrlC", "shortcut.ctrlV", "input.selectAll",
];

pub fn normalize(input: &Value, pc_button_ids: &[String]) -> Result<Value, String> {
    let root = input.as_object().ok_or("V2布局必须是对象")?;
    if root.get("schemaVersion").and_then(Value::as_u64) != Some(2) {
        return Err("V2布局版本不支持".into());
    }
    let portrait = normalize_orientation(root.get("portrait"), pc_button_ids)?;
    let landscape = normalize_orientation(root.get("landscape"), pc_button_ids)?;
    let mut result = serde_json::json!({
        "schemaVersion": 2,
        "portrait": portrait,
        "landscape": landscape,
    });
    // Explicit capability marker: older V2 payloads remain byte-for-byte
    // compatible and are never migrated merely by loading them.
    match (root.get("widgetGeometryVersion"), root.get("widgetGeometry")) {
        (None, None) => {}
        (Some(version), Some(raw)) if version.as_u64() == Some(1) => {
            let widgets = raw.as_object().ok_or("组件几何数据必须是对象")?;
            let portrait = normalize_widgets(widgets.get("portrait"))?;
            let landscape = normalize_widgets(widgets.get("landscape"))?;
            result["widgetGeometryVersion"] = Value::Number(1.into());
            result["widgetGeometry"] = serde_json::json!({
                "portrait": portrait,
                "landscape": landscape,
            });
        }
        _ => return Err("组件几何协议版本不匹配".into()),
    }
    Ok(result)
}

fn normalize_orientation(source: Option<&Value>, pc_button_ids: &[String]) -> Result<Vec<Value>, String> {
    let entries = source.and_then(Value::as_array).ok_or("布局缺少方向数据")?;
    if entries.is_empty() || entries.len() > MAX_PLACEMENTS {
        return Err("该方向布局按钮数量无效".into());
    }
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::with_capacity(entries.len());
    for entry in entries {
        let obj = entry.as_object().ok_or("布局按钮必须是对象")?;
        let text = |key| obj.get(key).and_then(Value::as_str);
        let id = text("id").ok_or("布局按钮缺少ID")?;
        let action = text("actionId").ok_or("布局按钮缺少动作")?;
        let container = text("container").ok_or("布局按钮缺少容器")?;
        if id.is_empty() || id.len() > MAX_ID || !seen.insert(id.to_string()) {
            return Err("布局按钮ID为空、重复或过长".into());
        }
        if !BUILTIN_ACTIONS.contains(&action)
            && !pc_button_ids.iter().any(|candidate| {
                action.strip_prefix("button.") == Some(candidate.as_str())
            })
        {
            return Err("布局引用了不存在的电脑动作".into());
        }
        if !CONTAINERS.contains(&container) { return Err("未知布局容器".into()); }
        let get_number = |name: &str| obj.get(name).and_then(Value::as_f64);
        let x = get_number("x").ok_or("坐标x无效")?;
        let y = get_number("y").ok_or("坐标y无效")?;
        let w = get_number("width").ok_or("按钮宽度无效")?;
        let h = get_number("height").ok_or("按钮高度无效")?;
        if ![x, y, w, h].iter().all(|v| v.is_finite())
            || x < 0.0 || y < 0.0 || w <= 0.0 || h <= 0.0
            || x + w > 1.00001 || y + h > 1.00001 {
            return Err("按钮超出当前方向可用屏幕".into());
        }
        let visible = obj.get("visible").and_then(Value::as_bool)
            .ok_or("按钮显隐必须为布尔值")?;
        let order = obj.get("order").and_then(Value::as_u64)
            .filter(|n| *n < MAX_PLACEMENTS as u64)
            .ok_or("按钮排序无效")?;

        let mut normalized = Map::new();
        normalized.insert("id".into(), Value::String(id.into()));
        normalized.insert("actionId".into(), Value::String(action.into()));
        normalized.insert("container".into(), Value::String(container.into()));
        for (name, value) in [("x", x), ("y", y), ("width", w), ("height", h)] {
            normalized.insert(name.into(),
                Value::Number(Number::from_f64(value).ok_or("无效数值")?));
        }
        normalized.insert("visible".into(), Value::Bool(visible));
        // Old V2 payloads without pinning remain scrollable by default.
        let pinned = match obj.get("pinned") {
            None => false,
            Some(v) => v.as_bool().ok_or("置顶必须为布尔值")?,
        };
        if pinned && container != "rail" { return Err("只有侧栏按钮可置顶".into()); }
        normalized.insert("pinned".into(), Value::Bool(pinned));
        normalized.insert("order".into(), Value::Number(order.into()));
        out.push(Value::Object(normalized));
    }
    Ok(out)
}

/// A phone running an older V2 editor cannot represent widget geometry.
/// Reject such a save rather than silently erasing already-synced layout work.
pub fn refuse_widget_geometry_downgrade(
    existing: Option<&Value>,
    incoming_normalized: &Value,
) -> Result<(), String> {
    let existing_has_widgets = existing
        .and_then(|v| v.get("widgetGeometryVersion"))
        .and_then(Value::as_u64) == Some(1)
        && existing.and_then(|v| v.get("widgetGeometry")).is_some();
    if existing_has_widgets && incoming_normalized.get("widgetGeometry").is_none() {
        return Err("旧版手机布局不支持已保存的悬浮组件；请更新客户端再编辑".into());
    }
    Ok(())
}

/// Normalized positions refer to the *usable* workbench viewport; do not
/// persist physical Redmi pixels or active recording/mouse-button state.
/// Keep the optional widget protocol distinct from official Button IDs.
fn normalize_widgets(source: Option<&Value>) -> Result<Vec<Value>, String> {
    let entries = source.and_then(Value::as_array).ok_or("组件缺少方向数据")?;
    if entries.len() > 3 { return Err("悬浮组件数量超限".into()); }
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::with_capacity(entries.len());
    for entry in entries {
        let obj = entry.as_object().ok_or("组件数据必须是对象")?;
        let id = obj.get("id").and_then(Value::as_str).ok_or("组件缺少ID")?;
        if !["voice", "mouse", "text"].contains(&id) || !seen.insert(id) {
            return Err("未知或重复悬浮组件ID".into());
        }
        let read = |key: &str| obj.get(key).and_then(Value::as_f64)
            .ok_or("组件坐标或尺寸无效");
        let x = read("x")?;
        let y = read("y")?;
        let width = read("width")?;
        let height = read("height")?;
        if ![x, y, width, height].iter().all(|v| v.is_finite())
            || x < 0.0 || y < 0.0 || width < 0.02 || height < 0.02
            || x + width > 1.00001 || y + height > 1.00001 {
            return Err("组件超出可用工作台或尺寸过小".into());
        }
        let visible = obj.get("visible").and_then(Value::as_bool)
            .ok_or("组件显隐必须为布尔值")?;
        out.push(serde_json::json!({
            "id": id, "x": x, "y": y,
            "width": width, "height": height, "visible": visible,
        }));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn valid() -> Value {
        json!({"schemaVersion":2,
            "portrait":[{"id":"ball.shortcut.altF4","actionId":"shortcut.altF4",
                "container":"ball","x":0.15,"y":0.12,"width":0.08,"height":0.10,
                "visible":true,"order":0}],
            "landscape":[
                {"id":"rail.shortcut.altF4","actionId":"shortcut.altF4",
                  "container":"rail","x":0.01,"y":0.13,"width":0.14,"height":0.08,
                  "visible":true,"order":0},
                {"id":"ball.keyboard","actionId":"input.keyboard",
                  "container":"ball","x":0.78,"y":0.74,"width":0.12,"height":0.09,
                  "visible":false,"order":1}]
        })
    }

    #[test]
    fn widget_geometry_is_optional_and_normalized_only_when_versioned() {
        let legacy = normalize(&valid(), &[]).unwrap();
        assert!(legacy.get("widgetGeometry").is_none());
        assert!(legacy.get("widgetGeometryVersion").is_none());
        let mut input = valid();
        input["widgetGeometryVersion"] = json!(1);
        input["widgetGeometry"] = json!({
            "portrait": [{"id":"voice","x":0.1,"y":0.7,
                "width":0.55,"height":0.12,"visible":true}],
            "landscape": [{"id":"mouse","x":0.4,"y":0.8,
                "width":0.22,"height":0.09,"visible":false}]
        });
        let result = normalize(&input, &[]).unwrap();
        assert_eq!(result["widgetGeometryVersion"], 1);
        assert_eq!(result["widgetGeometry"]["portrait"][0]["id"], "voice");
        assert_eq!(result["widgetGeometry"]["landscape"][0]["visible"], false);
    }

    #[test]
    fn old_client_cannot_erase_new_widget_geometry() {
        let legacy = normalize(&valid(), &[]).unwrap();
        assert!(refuse_widget_geometry_downgrade(Some(&legacy), &legacy).is_ok());
        let mut new_data = valid();
        new_data["widgetGeometryVersion"] = json!(1);
        new_data["widgetGeometry"] = json!({
            "portrait": [{"id":"voice","x":0.2,"y":0.4,"width":0.3,
                "height":0.2,"visible":true}],
            "landscape": []
        });
        let validated = normalize(&new_data, &[]).unwrap();
        assert!(refuse_widget_geometry_downgrade(Some(&validated), &legacy).is_err());
        assert!(refuse_widget_geometry_downgrade(Some(&validated), &validated).is_ok());
        assert!(refuse_widget_geometry_downgrade(None, &legacy).is_ok());
    }

    #[test]
    fn widget_geometry_rejects_unknown_duplicate_and_partial_payloads() {
        let mut input = valid();
        input["widgetGeometryVersion"] = json!(1);
        assert!(normalize(&input, &[]).is_err());
        input["widgetGeometry"] = json!({"portrait":[],"landscape":[]});
        assert!(normalize(&input, &[]).is_ok());
        input["widgetGeometry"]["portrait"] = json!([
            {"id":"voice","x":0.8,"y":0.8,"width":0.4,
                "height":0.1,"visible":true}
        ]);
        assert!(normalize(&input, &[]).is_err());
        input["widgetGeometry"]["portrait"][0]["x"] = json!(0.2);
        assert!(normalize(&input, &[]).is_ok());
        input["widgetGeometry"]["portrait"][0]["id"] = json!("taskSwitch");
        assert!(normalize(&input, &[]).is_err());
        input["widgetGeometry"]["portrait"][0]["id"] = json!("voice");
        let duplicate = input["widgetGeometry"]["portrait"][0].clone();
        input["widgetGeometry"]["portrait"].as_array_mut().unwrap().push(duplicate);
        assert!(normalize(&input, &[]).is_err());
        input["widgetGeometryVersion"] = json!(7);
        assert!(normalize(&input, &[]).is_err());
    }

    #[test]
    fn accepts_valid_separate_orientation_and_visibility() {
        let normalized = normalize(&valid(), &[]).unwrap();
        assert_eq!(normalized["schemaVersion"], 2);
        assert_eq!(normalized["portrait"].as_array().unwrap().len(), 1);
        assert_eq!(normalized["landscape"][1]["visible"], false);
        assert_eq!(normalized["landscape"][0]["pinned"], false);
        assert_eq!(normalized["landscape"][1]["actionId"], "input.keyboard");
    }

    #[test]
    fn supports_only_existing_official_pc_button_reference() {
        let mut v = valid();
        v["landscape"][0]["actionId"] = json!("button.real-id");
        assert!(normalize(&v, &["real-id".into()]).is_ok());
        assert!(normalize(&v, &["other-id".into()]).is_err());
        v["landscape"][0]["actionId"] = json!("script.cmd");
        assert!(normalize(&v, &["real-id".into()]).is_err());
    }

    #[test]
    fn rejects_duplicate_ids_and_invalid_position_without_partial_acceptance() {
        let mut v = valid();
        v["landscape"][1]["id"] = json!("rail.shortcut.altF4");
        assert!(normalize(&v, &[]).is_err());
        let mut v = valid();
        v["portrait"][0]["x"] = json!(0.99);
        assert!(normalize(&v, &[]).is_err());
        let mut v = valid();
        v["portrait"][0]["visible"] = json!("false");
        assert!(normalize(&v, &[]).is_err());
        let mut v = valid();
        v["landscape"][0]["pinned"] = json!("yes");
        assert!(normalize(&v, &[]).is_err());
        let mut v = valid();
        v["portrait"][0]["pinned"] = json!(true);
        assert!(normalize(&v, &[]).is_err());
        let mut v = valid();
        v["portrait"][0]["width"] = json!(-1);
        assert!(normalize(&v, &[]).is_err());
    }

    #[test]
    fn rejects_wrong_version_and_untrusted_extra_fields() {
        let mut v = valid();
        v["schemaVersion"] = json!(3);
        assert!(normalize(&v, &[]).is_err());
        v = valid();
        v["landscape"][0]["execute"] = json!("shutdown");
        let cleaned = normalize(&v, &[]).unwrap();
        assert!(cleaned["landscape"][0].get("execute").is_none());
    }
}
