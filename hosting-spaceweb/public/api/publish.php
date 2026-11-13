<?php
declare(strict_types=1);
require dirname(__DIR__) . '/private-loader.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); exit(json_encode(['error'=>'method'])); }

$version = (string)($_POST['version'] ?? '');
$code = (int)($_POST['version_code'] ?? 0);
$sha = strtolower((string)($_POST['sha256'] ?? ''));
$notes = fastvpn_cut(trim((string)($_POST['notes'] ?? '')), 1000);
$sig = (string)($_SERVER['HTTP_X_PUBLISH_SIGNATURE'] ?? '');
if (!preg_match('/^1\.0\.\d{1,8}$/', $version) || $code < 1 || !preg_match('/^[a-f0-9]{64}$/', $sha)) {
    http_response_code(400); exit(json_encode(['error'=>'invalid_metadata']));
}
$expected = hash_hmac('sha256', $version."\n".$code."\n".$sha, $cfg['publish_secret']);
if (!hash_equals($expected, $sig)) { http_response_code(403); exit(json_encode(['error'=>'signature'])); }

// Защита от случайной публикации старого тега поверх более новой версии.
$versionFile = dirname(__DIR__) . '/version.json';
if (is_file($versionFile)) {
    $current = json_decode((string)file_get_contents($versionFile), true);
    $currentCode = (int)($current['versionCode'] ?? 0);
    if ($code < $currentCode || ($code === $currentCode && !hash_equals((string)($current['sha256'] ?? ''), $sha))) {
        http_response_code(409);
        exit(json_encode(['error'=>'version_not_newer']));
    }
    if ($code === $currentCode && hash_equals((string)($current['sha256'] ?? ''), $sha)) {
        echo json_encode(['ok'=>true,'already_published'=>true,'version'=>$version,'sha256'=>$sha]);
        exit;
    }
}

$upload = $_FILES['apk'] ?? null;
if (!is_array($upload) || ($upload['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK || !is_uploaded_file($upload['tmp_name'])) {
    http_response_code(400); exit(json_encode(['error'=>'upload_failed']));
}
$size = (int)$upload['size'];
if ($size < 1000000 || $size > 100000000 || $size !== filesize($upload['tmp_name'])) {
    http_response_code(413); exit(json_encode(['error'=>'file_size']));
}
$magic = file_get_contents($upload['tmp_name'], false, null, 0, 4);
if ($magic !== "PK\x03\x04") { http_response_code(400); exit(json_encode(['error'=>'not_apk'])); }
$actual = hash_file('sha256', $upload['tmp_name']);
if (!hash_equals($sha, $actual)) { http_response_code(400); exit(json_encode(['error'=>'sha_mismatch'])); }

$releases = dirname(__DIR__) . '/releases';
if (!is_dir($releases) && !mkdir($releases, 0755, true)) {
    http_response_code(500); exit(json_encode(['error'=>'storage']));
}
$target = $releases . '/fast-vpn.apk';
$next = $target . '.new';
$backup = $target . '.previous';
if (!move_uploaded_file($upload['tmp_name'], $next)) {
    http_response_code(500); exit(json_encode(['error'=>'stage_failed']));
}
if (!hash_equals($sha, hash_file('sha256', $next))) {
    @unlink($next); http_response_code(400); exit(json_encode(['error'=>'staged_hash_mismatch']));
}
if (is_file($backup)) @unlink($backup);
if (is_file($target) && !rename($target, $backup)) {
    @unlink($next); http_response_code(500); exit(json_encode(['error'=>'backup_failed']));
}
if (!rename($next, $target)) {
    if (is_file($backup)) @rename($backup, $target);
    http_response_code(500); exit(json_encode(['error'=>'activate_failed']));
}
$meta = [
    'available'=>true, 'version'=>$version, 'versionCode'=>$code,
    'sha256'=>$sha, 'size'=>filesize($target), 'notes'=>$notes,
    'download'=>'/download.php', 'publishedAt'=>gmdate('c')
];
$tmp = dirname(__DIR__) . '/version.json.tmp';
$versionFile = dirname(__DIR__) . '/version.json';
if (file_put_contents($tmp, json_encode($meta, JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT), LOCK_EX) === false || !rename($tmp, $versionFile)) {
    @unlink($target);
    if (is_file($backup)) @rename($backup, $target);
    http_response_code(500); exit(json_encode(['error'=>'metadata_failed']));
}
if (is_file($backup)) @unlink($backup); // предыдущий APK удаляем только после успешной публикации
@chmod($target, 0644);
echo json_encode(['ok'=>true,'version'=>$version,'sha256'=>$sha]);
