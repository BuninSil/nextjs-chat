<?php
declare(strict_types=1);
$file = __DIR__ . '/releases/fast-vpn.apk';
if (!is_file($file) || filesize($file) < 1000000) {
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    exit('Новая версия пока не опубликована.');
}
header('Cache-Control: no-store');
header('Location: /releases/fast-vpn.apk', true, 302);
exit;
