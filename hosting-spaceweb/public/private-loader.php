<?php
declare(strict_types=1);
$privateDir = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'private';
$configFile = $privateDir . DIRECTORY_SEPARATOR . 'config.php';
if (!is_file($configFile)) { http_response_code(503); exit('Fast VPN backend is not installed.'); }
$cfg = require $configFile;
function fastvpn_cut(string $value, int $limit): string {
    if (function_exists('mb_substr')) return mb_substr($value, 0, $limit, 'UTF-8');
    if (preg_match('/^.{0,' . (int)$limit . '}/us', $value, $match)) return $match[0];
    return substr($value, 0, $limit);
}
try {
    $pdo = new PDO(
        'mysql:host='.$cfg['db_host'].';dbname='.$cfg['db_name'].';charset=utf8mb4',
        $cfg['db_user'],
        $cfg['db_pass'],
        [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION, PDO::ATTR_EMULATE_PREPARES=>false, PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]
    );
} catch (Throwable $e) {
    http_response_code(503);
    exit('Database unavailable.');
}
