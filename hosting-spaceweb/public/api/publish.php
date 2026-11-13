<?php
declare(strict_types=1);
require dirname(__DIR__) . '/private-loader.php';
header('Content-Type: application/json; charset=utf-8');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); exit(json_encode(['error'=>'method'])); }
$body = json_decode(file_get_contents('php://input'), true);
$version = (string)($body['version'] ?? '');
$code = (int)($body['version_code'] ?? 0);
$sha = strtolower((string)($body['sha256'] ?? ''));
$sig = (string)($_SERVER['HTTP_X_PUBLISH_SIGNATURE'] ?? '');
if (!preg_match('/^1\.0\.\d{1,8}$/', $version) || $code < 1 || !preg_match('/^[a-f0-9]{64}$/', $sha)) { http_response_code(400); exit(json_encode(['error'=>'invalid_metadata'])); }
$expected = hash_hmac('sha256', $version."\n".$code."\n".$sha, $cfg['publish_secret']);
if (!hash_equals($expected, $sig)) { http_response_code(403); exit(json_encode(['error'=>'signature'])); }
$incoming = $privateDir . '/storage/incoming/fast-vpn.apk';
if (!is_file($incoming) || filesize($incoming) < 1000000 || filesize($incoming) > 150000000) { http_response_code(400); exit(json_encode(['error'=>'file_missing_or_size'])); }
$actual = hash_file('sha256', $incoming);
if (!hash_equals($sha, $actual)) { http_response_code(400); exit(json_encode(['error'=>'sha_mismatch'])); }
$releases = dirname(__DIR__) . '/releases';
if (!is_dir($releases) && !mkdir($releases, 0755, true)) { http_response_code(500); exit(json_encode(['error'=>'storage'])); }
$target = $releases . '/fast-vpn.apk';
$next = $target . '.new';
$backup = $target . '.previous';
if (!rename($incoming, $next)) { http_response_code(500); exit(json_encode(['error'=>'stage_failed'])); }
if (is_file($backup)) @unlink($backup);
if (is_file($target) && !rename($target, $backup)) { @unlink($next); http_response_code(500); exit(json_encode(['error'=>'backup_failed'])); }
if (!rename($next, $target)) {
    if (is_file($backup)) @rename($backup, $target);
    http_response_code(500); exit(json_encode(['error'=>'activate_failed']));
}
$meta = ['available'=>true,'version'=>$version,'versionCode'=>$code,'sha256'=>$sha,'size'=>filesize($target),'download'=>'/download.php','publishedAt'=>gmdate('c')];
$tmp = dirname(__DIR__) . '/version.json.tmp';
$versionFile = dirname(__DIR__) . '/version.json';
if (file_put_contents($tmp, json_encode($meta, JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT), LOCK_EX) === false || !rename($tmp, $versionFile)) {
    @unlink($target);
    if (is_file($backup)) @rename($backup, $target);
    http_response_code(500); exit(json_encode(['error'=>'metadata_failed']));
}
if (is_file($backup)) @unlink($backup); // старая версия удаляется только после успешной активации новой
@chmod($target, 0644);
echo json_encode(['ok'=>true,'version'=>$version,'sha256'=>$sha]);
