<?php
declare(strict_types=1);
require dirname(__DIR__) . '/private-loader.php';
header('Content-Type: application/json; charset=utf-8'); header('Cache-Control: no-store');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); exit(json_encode(['error'=>'method'])); }
if (($_SERVER['HTTP_X_FASTVPN_CLIENT'] ?? '') !== $cfg['client_key']) { http_response_code(403); exit(json_encode(['error'=>'client'])); }
$length=(int)($_SERVER['CONTENT_LENGTH'] ?? 0); if($length<2 || $length>320000){http_response_code(413);exit(json_encode(['error'=>'size']));}
$in=json_decode(file_get_contents('php://input'),true); if(!is_array($in)){http_response_code(400);exit(json_encode(['error'=>'json']));}
$install=preg_replace('/[^a-f0-9-]/i','',(string)($in['install_id']??'')); $batch=preg_replace('/[^a-f0-9-]/i','',(string)($in['batch_id']??''));
$part=(int)($in['part']??0); $parts=(int)($in['parts']??0); $log=(string)($in['log']??'');
if(strlen($install)<16||strlen($install)>64||strlen($batch)<16||strlen($batch)>64||$part<1||$parts<1||$parts>200||$part>$parts||$log===''||strlen($log)>280000){http_response_code(400);exit(json_encode(['error'=>'invalid_payload']));}
$hash=hash_hmac('sha256',$install,$cfg['publish_secret']); $version=fastvpn_cut(trim((string)($in['version']??'unknown')),32); $model=fastvpn_cut(trim((string)($in['model']??'')),100); $android=fastvpn_cut(trim((string)($in['android']??'')),30);
$pdo->exec("CREATE TABLE IF NOT EXISTS device_logs (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,batch_id CHAR(64) NOT NULL,install_hash CHAR(64) NOT NULL,app_version VARCHAR(32) NOT NULL DEFAULT '',device_model VARCHAR(100) NOT NULL DEFAULT '',android_version VARCHAR(30) NOT NULL DEFAULT '',part_no SMALLINT UNSIGNED NOT NULL,total_parts SMALLINT UNSIGNED NOT NULL,log_text MEDIUMTEXT NOT NULL,created_at DATETIME NOT NULL,PRIMARY KEY(id),UNIQUE KEY uq_batch_part(batch_id,part_no),KEY idx_created(created_at),KEY idx_install_created(install_hash,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$q=$pdo->prepare('SELECT COUNT(*) FROM device_logs WHERE install_hash=? AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 5 MINUTE)');$q->execute([$hash]);if((int)$q->fetchColumn()>=200){http_response_code(429);exit(json_encode(['error'=>'rate_limit']));}
$q=$pdo->prepare('INSERT INTO device_logs(batch_id,install_hash,app_version,device_model,android_version,part_no,total_parts,log_text,created_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE log_text=VALUES(log_text),total_parts=VALUES(total_parts)');
$q->execute([$batch,$hash,$version,$model,$android,$part,$parts,$log]); echo json_encode(['ok'=>true,'batch_id'=>$batch,'part'=>$part,'parts'=>$parts],JSON_UNESCAPED_UNICODE);