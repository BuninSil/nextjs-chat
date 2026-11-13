<?php
declare(strict_types=1);
require dirname(__DIR__) . '/private-loader.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); exit(json_encode(['error'=>'method'])); }
if (($_SERVER['HTTP_X_FASTVPN_CLIENT'] ?? '') !== $cfg['client_key']) { http_response_code(403); exit(json_encode(['error'=>'client'])); }
$len = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
if ($len < 2 || $len > 16000) { http_response_code(413); exit(json_encode(['error'=>'size'])); }
$in = json_decode(file_get_contents('php://input'), true);
if (!is_array($in)) { http_response_code(400); exit(json_encode(['error'=>'json'])); }
$install = preg_replace('/[^a-f0-9-]/i', '', (string)($in['install_id'] ?? ''));
if (strlen($install) < 16 || strlen($install) > 64) { http_response_code(400); exit(json_encode(['error'=>'install_id'])); }
$installHash = hash_hmac('sha256', $install, $cfg['publish_secret']);
$ip = (string)($_SERVER['REMOTE_ADDR'] ?? '');
$ipHash = hash_hmac('sha256', $ip, $cfg['publish_secret']);
$rate = $pdo->prepare('SELECT COUNT(*) FROM reports WHERE install_hash=? AND created_at > DATE_SUB(UTC_TIMESTAMP(), INTERVAL 5 MINUTE)');
$rate->execute([$installHash]);
if ((int)$rate->fetchColumn() > 0) { http_response_code(429); exit(json_encode(['error'=>'rate_limit'])); }
$rate = $pdo->prepare('SELECT COUNT(*) FROM reports WHERE ip_hash=? AND created_at > DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY)');
$rate->execute([$ipHash]);
if ((int)$rate->fetchColumn() >= 12) { http_response_code(429); exit(json_encode(['error'=>'daily_limit'])); }
$version = mb_substr(trim((string)($in['version'] ?? 'unknown')), 0, 32);
$versionCode = max(0, min(4294967295, (int)($in['version_code'] ?? 0)));
$manufacturer = mb_substr(trim((string)($in['manufacturer'] ?? '')), 0, 80);
$model = mb_substr(trim((string)($in['model'] ?? '')), 0, 100);
$android = mb_substr(trim((string)($in['android'] ?? '')), 0, 30);
$sdk = max(0, min(1000, (int)($in['sdk'] ?? 0)));
$vpn = in_array(($in['vpn_state'] ?? ''), ['connected','connecting','off'], true) ? $in['vpn_state'] : 'unknown';
$network = in_array(($in['network'] ?? ''), ['Wi-Fi','мобильная сеть','Ethernet','другая сеть','нет сети','неизвестно'], true) ? $in['network'] : 'unknown';
$description = mb_substr(trim((string)($in['description'] ?? '')), 0, 500);
$diagnostics = mb_substr((string)($in['diagnostics'] ?? ''), 0, 9000);
$ticket = 'FV-' . gmdate('ymd') . '-' . strtoupper(bin2hex(random_bytes(3)));
$q = $pdo->prepare('INSERT INTO reports (ticket,install_hash,ip_hash,app_version,version_code,manufacturer,device_model,android_version,sdk,vpn_state,network_type,description,diagnostics,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())');
$q->execute([$ticket,$installHash,$ipHash,$version,$versionCode,$manufacturer,$model,$android,$sdk,$vpn,$network,$description,$diagnostics]);
http_response_code(201);
echo json_encode(['ok'=>true,'ticket'=>$ticket], JSON_UNESCAPED_UNICODE);
