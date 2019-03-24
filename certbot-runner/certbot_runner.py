import boto3
import certbot.main
import os
import tarfile
import zipfile

def handler(event, context):
    subdomain = os.environ['SUBDOMAIN']
    s3 = boto3.client('s3')
    s3.download_file(
        f"{os.environ['AWS_REGION']}.liquidity-certbot-runner-infrastructure-{subdomain}",
        'state.tar',
        '/tmp/state.tar'
    )
    with tarfile.open('/tmp/state.tar', 'r:') as tar:
        tar.extractall('/tmp/config-dir')
    certbot.main.main([
        'certonly',
        '--non-interactive',
        '--config-dir', '/tmp/config-dir',
        '--work-dir', '/tmp/work-dir',
        '--logs-dir', '/tmp/logs-dir',
        '--email', 'admin@liquidityapp.com',
        '--agree-tos',
        '--dns-route53',
        '-d', f'{subdomain}.liquidityapp.com',
    ])
    with tarfile.open('/tmp/state.tar', 'w:') as tar:
        tar.add('/tmp/config-dir', arcname = '')
    s3.upload_file(
        '/tmp/state.tar',
        f"{os.environ['AWS_REGION']}.liquidity-certbot-runner-infrastructure-{subdomain}",
        'state.tar'
    )
    with zipfile.ZipFile('/tmp/certbundle.zip', 'x') as zip:
        zip.write(f'/tmp/config-dir/live/{subdomain}.liquidityapp.com/privkey.pem', arcname = 'privkey.pem')
        zip.write(f'/tmp/config-dir/live/{subdomain}.liquidityapp.com/fullchain.pem', arcname = 'fullchain.pem')
    s3.upload_file(
        '/tmp/certbundle.zip',
        f"{os.environ['AWS_REGION']}.liquidity-certbot-runner-infrastructure-{subdomain}",
        'certbundle.zip'
    )
