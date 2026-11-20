-- Foto de perfil enviada pela própria pessoa. Aditivo: sem foto (NULL) tudo continua como antes.
-- photo_key é a chave no FileStorage (sem "/"); photo_updated_at versiona a URL e o ETag.
ALTER TABLE users ADD COLUMN photo_key varchar(255) NULL;
ALTER TABLE users ADD COLUMN photo_updated_at timestamptz NULL;
