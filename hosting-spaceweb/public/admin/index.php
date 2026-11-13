<?php
declare(strict_types=1);
ini_set('session.use_strict_mode', '1');
$isSecureRequest = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') || ((int)($_SERVER['SERVER_PORT'] ?? 0) === 443);
session_set_cookie_params(['lifetime'=>0, 'path'=>'/admin/', 'secure'=>$isSecureRequest, 'httponly'=>true, 'samesite'=>'Strict']);
session_start();
require dirname(__DIR__) . '/private-loader.php';
function eh(string $s): string { return htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(24));
$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['login_password'])) {
    if (!hash_equals($_SESSION['csrf'], (string)($_POST['csrf'] ?? ''))) { http_response_code(403); exit('CSRF'); }
    $tries = (int)($_SESSION['login_tries'] ?? 0);
    if ($tries >= 8) $error = 'Слишком много попыток. Закрой браузер и повтори позже.';
    elseif (password_verify((string)$_POST['login_password'], $cfg['admin_password_hash'])) {
        session_regenerate_id(true);
        $_SESSION['admin'] = true;
        $_SESSION['login_tries'] = 0;
        $_SESSION['csrf'] = bin2hex(random_bytes(24));
    } else {
        $_SESSION['login_tries'] = $tries + 1;
        $error = 'Неверный пароль.';
    }
}
if (isset($_GET['logout'])) { $_SESSION = []; session_destroy(); header('Location: /admin/'); exit; }
if (empty($_SESSION['admin'])):
?><!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fast VPN · Админка</title>
<style>body{background:#0d0f13;color:#eee;font:16px system-ui;display:grid;place-items:center;min-height:100vh;margin:0}main{width:min(90%,400px);padding:24px;background:#161a21;border:1px solid #2a3039;border-radius:16px}input,button{box-sizing:border-box;width:100%;padding:13px;margin-top:10px;border-radius:9px}input{background:#0f1216;border:1px solid #333;color:white}button{background:#1ed760;border:0;font-weight:bold}.err{color:#ff6b64}</style>
<main><h1>⚡ Fast VPN</h1><p>Закрытая панель обращений</p><?php if($error):?><p class="err"><?=eh($error)?></p><?php endif;?><form method="post"><input type="hidden" name="csrf" value="<?=eh($_SESSION['csrf'])?>"><input type="password" name="login_password" placeholder="Пароль администратора" required autofocus><button>Войти</button></form></main></html><?php
exit; endif;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($_SESSION['csrf'], (string)($_POST['csrf'] ?? ''))) { http_response_code(403); exit('CSRF'); }
    $action = (string)($_POST['action'] ?? 'save');
    if ($action === 'delete_one') {
        $ticket = (string)($_POST['ticket'] ?? '');
        $q = $pdo->prepare("DELETE FROM reports WHERE ticket=? AND status IN ('resolved','duplicate')");
        $q->execute([$ticket]);
        $_SESSION['flash'] = $q->rowCount() ? 'Обращение удалено.' : 'Удалить можно только решённые обращения и дубликаты.';
    } elseif ($action === 'delete_selected') {
        $tickets = array_values(array_unique(array_filter(array_map('strval', (array)($_POST['tickets'] ?? [])), static fn($v) => $v !== '' && strlen($v) <= 24)));
        if ($tickets) {
            $marks = implode(',', array_fill(0, count($tickets), '?'));
            $q = $pdo->prepare("DELETE FROM reports WHERE ticket IN ($marks) AND status IN ('resolved','duplicate')");
            $q->execute($tickets);
            $_SESSION['flash'] = 'Удалено обращений: ' . $q->rowCount() . '.';
        } else $_SESSION['flash'] = 'Сначала выбери обращения.';
    } elseif (isset($_POST['ticket'], $_POST['status'])) {
        $status = in_array($_POST['status'], ['new','in_progress','resolved','duplicate'], true) ? $_POST['status'] : 'new';
        $note = fastvpn_cut(trim((string)($_POST['admin_note'] ?? '')), 2000);
        $q = $pdo->prepare('UPDATE reports SET status=?, admin_note=?, updated_at=UTC_TIMESTAMP() WHERE ticket=?');
        $q->execute([$status, $note, (string)$_POST['ticket']]);
        $_SESSION['flash'] = 'Изменения сохранены.';
    }
    $redirectFilter = (string)($_POST['return_status'] ?? '');
    header('Location: /admin/' . (in_array($redirectFilter, ['new','in_progress','resolved','duplicate'], true) ? '?status=' . urlencode($redirectFilter) : ''));
    exit;
}
$filter = (string)($_GET['status'] ?? '');
$allowed = ['new','in_progress','resolved','duplicate'];
$categoryLabels = ['vpn'=>'Подключение','network'=>'Сеть и скорость','update'=>'Обновление','other'=>'Другое'];
$statusLabels = ['new'=>'Новые','in_progress'=>'В работе','resolved'=>'Решённые','duplicate'=>'Дубликаты'];
$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 30;
$count = $pdo->query("SELECT status, COUNT(*) c FROM reports GROUP BY status")->fetchAll(PDO::FETCH_KEY_PAIR);
$totalRows = array_sum(array_map('intval', $count));
$countSql = 'SELECT COUNT(*) FROM reports';
$countArgs = [];
if (in_array($filter, $allowed, true)) { $countSql .= ' WHERE status=?'; $countArgs[] = $filter; }
$countQuery = $pdo->prepare($countSql); $countQuery->execute($countArgs);
$totalRows = (int)$countQuery->fetchColumn();
$totalPages = max(1, (int)ceil($totalRows / $perPage));
$page = min($page, $totalPages);
$offset = ($page - 1) * $perPage;
$sql = 'SELECT ticket,category,app_version,manufacturer,device_model,android_version,vpn_state,network_type,description,diagnostics,status,admin_note,created_at FROM reports';
$args = [];
if (in_array($filter, $allowed, true)) { $sql .= ' WHERE status=?'; $args[] = $filter; }
$sql .= " ORDER BY created_at DESC, id DESC LIMIT $perPage OFFSET $offset";
$q = $pdo->prepare($sql); $q->execute($args); $rows = $q->fetchAll(PDO::FETCH_ASSOC);
$flash = (string)($_SESSION['flash'] ?? ''); unset($_SESSION['flash']);
$tabLabels = [''=>'Все','new'=>'Новые','in_progress'=>'В работе','resolved'=>'Решённые','duplicate'=>'Дубликаты'];
?><!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fast VPN · Обращения</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#0d0f13;color:#eceff3;font:14px system-ui}header{padding:20px max(4%,calc((100% - 1100px)/2));border-bottom:1px solid #292f38;display:flex;justify-content:space-between;align-items:center}main{max-width:1100px;margin:auto;padding:22px 4%}.stats{display:flex;gap:10px;flex-wrap:wrap}.stat,.ticket{background:#161a21;border:1px solid #2a3039;border-radius:12px;padding:14px}.stat{min-width:110px}.stat b{display:block;font-size:24px}.filters{display:flex;gap:8px;flex-wrap:wrap;margin:18px 0}.filters a{color:#cbd2dc;text-decoration:none;border:1px solid #333b46;border-radius:20px;padding:8px 13px}.filters a.active{background:#1b3327;border-color:#247a46;color:#7cf0a5}.ticket{margin:12px 0}.muted{color:#929ba7;font-size:12px}.ticket h3{margin:0 0 8px}.desc{white-space:pre-wrap;margin:10px 0}.diag{white-space:pre-wrap;overflow-wrap:anywhere;max-height:70vh;overflow:auto;background:#0e1217;padding:12px;border-radius:8px;color:#b7c1ce;line-height:1.55}.diag-tools{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.copy-diag{margin:0;padding:6px 10px;font-size:12px;cursor:pointer}.copy-state{color:#929ba7;font-size:12px}input,select,textarea{background:#0e1217;color:#eee;border:1px solid #343b46;border-radius:8px;padding:9px}textarea{width:100%;min-height:55px;margin-top:8px}button{background:#1ed760;color:#07140d;border:0;border-radius:8px;padding:10px 14px;font-weight:700;margin-top:8px;cursor:pointer}.danger{background:#422126;color:#ffb8bd;border:1px solid #78383f}.tag{color:#1ed760}a{color:#aeb8c5}.ticket-top{display:flex;gap:10px;align-items:flex-start}.ticket-top input{margin-top:3px;accent-color:#1ed760}.toolbar{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;padding:12px;background:#141820;border:1px solid #29313b;border-radius:12px}.flash{padding:10px 13px;border:1px solid #315c42;background:#14251b;border-radius:9px;color:#a9efbf;margin:14px 0}.empty{padding:30px;text-align:center;color:#929ba7}
</style><header><b>⚡ Fast VPN · Центр обращений</b><a href="?logout=1">Выйти</a></header><main>
<div class="stats"><?php foreach(['new'=>'Новые','in_progress'=>'В работе','resolved'=>'Решённые','duplicate'=>'Дубликаты'] as $k=>$label):?><div class="stat"><b><?= (int)($count[$k]??0)?></b><span class="muted"><?=eh($label)?></span></div><?php endforeach;?></div>
<nav class="filters"><?php foreach($tabLabels as $key=>$label):?><a class="<?=($filter===$key?'active':'')?>" href="<?= $key===''?'/admin/':'?status='.eh($key) ?>"><?=eh($label)?><?php if($key!=='' ):?> · <?= (int)($count[$key]??0) ?><?php else:?> · <?=array_sum(array_map('intval',$count))?><?php endif;?></a><?php endforeach;?></nav>
<?php if($flash!==''):?><div class="flash"><?=eh($flash)?></div><?php endif;?>
<?php if(in_array($filter,['resolved','duplicate'],true)):?><form id="bulkDelete" method="post" onsubmit="return confirm('Удалить выбранные решённые обращения и дубликаты без возможности восстановления?')"><input type="hidden" name="csrf" value="<?=eh($_SESSION['csrf'])?>"><input type="hidden" name="action" value="delete_selected"><input type="hidden" name="return_status" value="<?=eh($filter)?>"></form><div class="toolbar"><label><input type="checkbox" id="selectAll"> Выбрать все на странице</label><button class="danger" type="submit" form="bulkDelete">Удалить выбранные</button></div><?php endif;?>
<?php foreach($rows as $r):?><article class="ticket"><div class="ticket-top"><?php if(in_array($filter,['resolved','duplicate'],true)):?><input type="checkbox" name="tickets[]" value="<?=eh($r['ticket'])?>" form="bulkDelete" aria-label="Выбрать <?=eh($r['ticket'])?>"><?php endif;?><div style="flex:1;min-width:0"><h3><?=eh($r['ticket'])?> <span class="tag">· <?=eh($statusLabels[$r['status']] ?? $r['status'])?></span> <span class="muted">· <?=eh($categoryLabels[$r['category']] ?? 'Другое')?></span></h3>
<div class="muted"><?=eh($r['created_at'])?> UTC · <?=eh($r['app_version'])?> · <?=eh($r['manufacturer'].' '.$r['device_model'])?> · Android <?=eh($r['android_version'])?> · <?=eh($r['network_type'])?> · VPN <?=eh($r['vpn_state'])?></div></div></div>
<?php if($r['description']!==''):?><p class="desc"><?=eh($r['description'])?></p><?php endif;?>
<details><summary>Диагностика · <?=strlen((string)$r['diagnostics'])?> байт</summary><div class="diag-tools"><button type="button" class="copy-diag" onclick="copyDiag(this)">Копировать весь отчёт</button><span class="copy-state" aria-live="polite"></span></div><pre class="diag"><?=eh($r['diagnostics'])?></pre></details>
<form method="post"><input type="hidden" name="csrf" value="<?=eh($_SESSION['csrf'])?>"><input type="hidden" name="ticket" value="<?=eh($r['ticket'])?>"><input type="hidden" name="return_status" value="<?=eh($filter)?>">
<select name="status"><?php foreach($allowed as $s):?><option value="<?=$s?>" <?=$r['status']===$s?'selected':''?>><?=eh($statusLabels[$s])?></option><?php endforeach;?></select>
<textarea name="admin_note" placeholder="Внутренняя заметка, видна только тебе"><?=eh($r['admin_note'])?></textarea><button name="action" value="save">Сохранить</button><?php if(in_array($r['status'],['resolved','duplicate'],true)):?> <button class="danger" type="submit" name="action" value="delete_one" onclick="return confirm('Удалить это обращение без возможности восстановления?')">Удалить</button><?php endif;?></form></article><?php endforeach;?>
<?php if(!$rows):?><div class="empty">В этой вкладке пока пусто. Даже у багов иногда выходной.</div><?php endif;?>
<?php if($totalPages>1):?><nav class="filters">
<?php if($page>1):?><a href="?<?=in_array($filter,$allowed,true)?'status='.urlencode($filter).'&':''?>page=<?=$page-1?>">← Новее</a><?php endif;?>
<span class="muted">Страница <?=$page?> из <?=$totalPages?> · всего <?=$totalRows?></span>
<?php if($page<$totalPages):?><a href="?<?=in_array($filter,$allowed,true)?'status='.urlencode($filter).'&':''?>page=<?=$page+1?>">Старее →</a><?php endif;?>
</nav><?php endif;?>
</main><script>
async function copyDiag(button) {
    const details = button.closest('details');
    const text = details ? details.querySelector('.diag')?.textContent || '' : '';
    const state = details?.querySelector('.copy-state');
    try {
        if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(text);
        else {
            const field = document.createElement('textarea'); field.value = text;
            field.style.position = 'fixed'; field.style.opacity = '0'; document.body.appendChild(field);
            field.select(); const ok = document.execCommand('copy'); field.remove();
            if (!ok) throw new Error('copy failed');
        }
        if (state) state.textContent = 'Скопировано';
    } catch (_) { if (state) state.textContent = 'Не удалось скопировать'; }
}
const selectAll=document.getElementById('selectAll');
if(selectAll) selectAll.addEventListener('change',()=>document.querySelectorAll('input[name="tickets[]"]').forEach(x=>x.checked=selectAll.checked));
</script></html>
