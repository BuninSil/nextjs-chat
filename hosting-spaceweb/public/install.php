<?php
declare(strict_types=1);
session_start();
$privateDir = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'private';
$keyFile = $privateDir . DIRECTORY_SEPARATOR . 'setup.key';
$configFile = $privateDir . DIRECTORY_SEPARATOR . 'config.php';
function h(string $v): string { return htmlspecialchars($v, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
if (is_file($configFile)) { http_response_code(404); exit('Установщик уже использован. Удалите install.php.'); }
if (!is_file($keyFile)) { http_response_code(403); exit('Сначала создайте /private/setup.key.'); }
$error = '';
$created = false;
$adminPassword = '';
$publishSecret = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $provided = (string)($_POST['setup_key'] ?? '');
    $expected = trim((string)file_get_contents($keyFile));
    if ($expected === '' || !hash_equals($expected, $provided)) {
        $error = 'Неверный ключ установки.';
    } else {
        $host = trim((string)($_POST['db_host'] ?? ''));
        $name = trim((string)($_POST['db_name'] ?? ''));
        $user = trim((string)($_POST['db_user'] ?? ''));
        $pass = (string)($_POST['db_pass'] ?? '');
        try {
            if (!preg_match('/^[a-zA-Z0-9_.:-]{1,190}$/', $host) || $name === '' || $user === '') {
                throw new RuntimeException('Проверь реквизиты базы данных.');
            }
            $pdo = new PDO("mysql:host={$host};dbname={$name};charset=utf8mb4", $user, $pass, [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_EMULATE_PREPARES => false,
            ]);
            $schema = file_get_contents($privateDir . DIRECTORY_SEPARATOR . 'schema.sql');
            foreach (array_filter(array_map('trim', explode(';', (string)$schema))) as $sql) $pdo->exec($sql);
            $adminPassword = bin2hex(random_bytes(10));
            $publishSecret = bin2hex(random_bytes(32));
            $config = [
                'db_host' => $host, 'db_name' => $name, 'db_user' => $user, 'db_pass' => $pass,
                'admin_password_hash' => password_hash($adminPassword, PASSWORD_DEFAULT),
                'publish_secret' => $publishSecret,
                'client_key' => 'fastvpn-android-v1',
            ];
            if (!is_dir($privateDir . '/storage/incoming')) mkdir($privateDir . '/storage/incoming', 0700, true);
            $php = "<?php\nreturn " . var_export($config, true) . ";\n";
            if (file_put_contents($configFile, $php, LOCK_EX) === false) throw new RuntimeException('Не удалось записать private/config.php.');
            @chmod($configFile, 0600);
            @unlink($keyFile);
            $created = true;
        } catch (Throwable $e) {
            $error = 'Установка не завершена: ' . $e->getMessage();
        }
    }
}
?><!doctype html>
<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Установка Fast VPN</title>
<style>
body{margin:0;background:#0d0f13;color:#eceff3;font:16px system-ui;display:grid;min-height:100vh;place-items:center}
main{width:min(92%,520px);background:#161a21;border:1px solid #2a3039;border-radius:18px;padding:24px;box-sizing:border-box}
input{width:100%;box-sizing:border-box;margin:7px 0 14px;padding:12px;border-radius:10px;border:1px solid #343b46;background:#0f1216;color:#fff}
button{width:100%;padding:13px;border:0;border-radius:10px;background:#1ed760;color:#07140d;font-weight:700}
small{color:#9aa2ad}.err{color:#ff6961}.secret{overflow-wrap:anywhere;background:#0e1217;padding:12px;border-radius:8px}
</style><main>
<h1>⚡ Fast VPN · Установка</h1>
<?php if ($created): ?>
<h2>Установка завершена</h2>
<p>Сохрани эти данные сейчас. Пароль и секрет больше не будут показаны.</p>
<p>Пароль администратора:</p><p class="secret"><b><?=h($adminPassword)?></b></p>
<p>Секрет публикации APK для GitHub Actions:</p><p class="secret"><b><?=h($publishSecret)?></b></p>
<p>Теперь удали <b>install.php</b>. Ключ установки удалён автоматически.</p>
<?php else: ?>
<?php if ($error !== ''): ?><p class="err"><?=h($error)?></p><?php endif; ?>
<form method="post" autocomplete="off">
<label>Ключ установки из /private/setup.key</label><input name="setup_key" required>
<label>Хост MySQL из панели</label><input name="db_host" required>
<label>Имя базы</label><input name="db_name" required>
<label>Пользователь БД</label><input name="db_user" required>
<label>Пароль БД</label><input name="db_pass" type="password" required>
<button>Создать базу и закрытую админку</button>
</form><p><small>Реквизиты не отправляются сторонним сервисам. Установщик создаёт конфигурацию вне публичной папки сайта.</small></p>
<?php endif; ?>
</main></html>
