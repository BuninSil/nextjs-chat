<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$file = dirname(__DIR__) . '/version.json';
if (!is_file($file)) { http_response_code(200); echo json_encode(['available'=>false], JSON_UNESCAPED_UNICODE); exit; }
readfile($file);
