import awscli.clidriver
import certbot.main
import os

def handler(event, context):
    domain = os.environ['LETSENCRYPT_DOMAIN']
    email = os.environ['LETSENCRYPT_EMAIL']
    aws = awscli.clidriver.create_clidriver()
    aws.main([
        's3', 'sync', '--delete',
        f"s3://{os.environ['AWS_REGION']}.liquidity-certbot-runner-{domain}",
        '/tmp/config-dir/'
    ])
    certbot.main.main([
        'certonly',
        '--non-interactive',
        '--config-dir', '/tmp/config-dir/',
        '--work-dir', '/tmp/work-dir/',
        '--logs-dir', '/tmp/logs-dir/',
        '--email', email,
        '--agree-tos',
        '--dns-route53',
        '-d', domain,
    ])
    aws.main([
        's3', 'sync', '--delete',
        '/tmp/config-dir/',
        f"s3://{os.environ['AWS_REGION']}.liquidity-certbot-runner-{domain}"
    ])
