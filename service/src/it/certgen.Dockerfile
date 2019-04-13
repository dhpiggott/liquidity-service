FROM alpine:edge as builder
RUN apk --no-cache add bash openssl zip
# From https://letsencrypt.org/docs/certificates-for-localhost/
RUN /bin/bash -c 'openssl req \
  -x509 \
  -newkey rsa:2048 \
  -nodes \
  -extensions EXT \
  -subj "/CN=localhost" \
  -config <(printf "[dn]\nCN=localhost\n[req]\ndistinguished_name = dn\n[EXT]\nsubjectAltName=DNS:localhost\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth") \
  -keyout privkey.pem \
  -out fullchain.pem'
RUN zip certbundle.zip privkey.pem fullchain.pem

FROM nginx:1.15
COPY --from=builder certbundle.zip /usr/share/nginx/html
