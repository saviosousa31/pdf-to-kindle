# PDF para EPUB — App Android
## Como gerar o APK pelo GitHub (gratuito, sem instalar nada)

---

### PASSO 1 — Criar conta no GitHub
1. Acesse https://github.com
2. Clique em "Sign up" e crie uma conta gratuita
3. Confirme seu e-mail

---

### PASSO 2 — Criar o repositório
1. Clique no botão verde "New" (canto superior esquerdo)
2. Preencha o nome: `pdf-para-epub-android`
3. Marque "Private" (privado, só você vê)
4. Clique em "Create repository"

---

### PASSO 3 — Fazer upload dos arquivos
1. Na página do repositório vazio, clique em "uploading an existing file"
2. Arraste a pasta `android-project` inteira para a área de upload
   - OU use o botão "choose your files" e selecione todos os arquivos
3. **IMPORTANTE:** Mantenha a estrutura de pastas exatamente como está
4. Clique em "Commit changes"

---

### PASSO 4 — Executar o build
1. Clique na aba "Actions" no menu do repositório
2. Você verá o workflow "Build APK"
3. Clique em "Run workflow" → "Run workflow" (botão verde)
4. Aguarde de 5 a 10 minutos (aparece um círculo girando)

---

### PASSO 5 — Baixar o APK
1. Quando o círculo ficar verde (✓), clique no workflow concluído
2. Role a página até "Artifacts"
3. Clique em "PDF-para-EPUB-Android" para baixar o ZIP
4. Dentro do ZIP está o arquivo `app-debug.apk`

---

### PASSO 6 — Instalar no celular
1. Transfira o `app-debug.apk` para seu celular Android
2. No celular, abra o arquivo (pode ser pelo gerenciador de arquivos)
3. Se aparecer "Instalar de fontes desconhecidas":
   - Vá em Configurações → Segurança → Fontes desconhecidas → Ativar
   - OU Configurações → Apps → Menu → Acesso especial → Instalar apps desconhecidos
4. Toque em "Instalar"

---

### Como usar o app

1. **Selecionar PDF** — toque no botão e escolha o arquivo PDF
   - Nomeie o arquivo como: `Titulo - Autor.pdf`
   - Exemplo: `A Metamorfose - Franz Kafka.pdf`

2. **Buscar Capa** — o app busca automaticamente nas fontes:
   Amazon, Google Books, Open Library, Bing, DuckDuckGo, Goodreads, Archive.org

3. **Escolher capa** — role o carrossel e toque na capa desejada
   - Toque em "Carregar mais capas" para ver mais opções

4. **Converter** — toque em "Converter para EPUB"
   - A barra de progresso mostra o andamento
   - O EPUB é salvo automaticamente em Downloads

5. **Enviar por e-mail** (opcional):
   - Configure em ⚙ Configurações antes
   - Gmail: use uma Senha de App em myaccount.google.com/apppasswords

---

### Solução de problemas

| Problema | Solução |
|---|---|
| Workflow falha | Veja os logs em Actions e copie o erro |
| APK não instala | Ative "Instalar apps desconhecidos" nas configurações do celular |
| Capa não encontrada | Verifique o nome do arquivo (Título - Autor.pdf) |
| E-mail falha (Gmail) | Use Senha de App, não a senha normal |
| EPUB sem texto | O PDF pode ser escaneado (imagem) — sem suporte a OCR |
