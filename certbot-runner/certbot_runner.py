import boto3
import certbot.main
import os
import tarfile
import zipfile

def handler(event, context):
    domain = os.environ['LETSENCRYPT_DOMAIN']
    email = os.environ['LETSENCRYPT_EMAIL']
    s3 = boto3.client('s3')
    s3.download_file(
        f"{os.environ['AWS_REGION']}.liquidity-certbot-runner-{domain}",
        'certbot-runner-state.tar',
        '/tmp/certbot-runner-state.tar'
    )
    with tarfile.open('/tmp/certbot-runner-state.tar', 'r:') as tar:
        tar.extractall('/tmp/config-dir')
    certbot.main.main([
        'certonly',
        '--non-interactive',
        '--config-dir', '/tmp/config-dir',
        '--work-dir', '/tmp/work-dir',
        '--logs-dir', '/tmp/logs-dir',
        '--email', email,
        '--agree-tos',
        '--dns-route53',
        '-d', domain,
    ])
    with tarfile.open('/tmp/certbot-runner-state.tar', 'w:') as tar:
        tar.add('/tmp/config-dir', arcname = '')
    s3.upload_file(
        '/tmp/certbot-runner-state.tar',
        f"{os.environ['AWS_REGION']}.liquidity-certbot-runner-{domain}",
        'certbot-runner-state.tar'
    )
    with zipfile.ZipFile('/tmp/certbot-runner-data.zip', 'x') as zip:
        zip.write(f'/tmp/config-dir/live/{domain}/privkey.pem', arcname = 'privkey.pem')
        zip.write(f'/tmp/config-dir/live/{domain}/fullchain.pem', arcname = 'fullchain.pem')
    s3.upload_file(
        '/tmp/certbot-runner-data.zip',
        f"{os.environ['AWS_REGION']}.liquidity-certbot-runner-{domain}",
        'certbot-runner-data.zip'
    )
