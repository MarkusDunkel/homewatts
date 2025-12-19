# 🚀 Google Cloud VM Setup – Full Reset & Deployment Guide

This guide describes **Option A: Start Fresh**, which completely resets your VM (deletes the broken one) and creates a new, clean Debian 12 instance with attached persistent disk, Docker, and your application deployed.

---

## 🧹 Step 1 – Delete the Broken VM and Old Disks

```bash
gcloud config set project pv-management-app

# Stop and delete the old instance (boot + data disks)
gcloud compute instances delete app-vm   --zone=europe-west1-b --quiet

# Delete the extra database disk if it still exists
gcloud compute disks delete db-disk   --zone=europe-west1-b --quiet
```

---

## ⚙️ Step 2 – Create a Brand‑New VM

```bash
gcloud compute instances create app-vm   --zone=europe-west1-b   --machine-type=e2-small   --image-family=debian-12   --image-project=debian-cloud   --boot-disk-size=30GB   --tags=http-server,https-server
```

---

## 🪣 Step 3 – Create and Attach a New Persistent Disk

```bash
# Create a 30 GB balanced disk for DB data
gcloud compute disks create db-disk   --size=30GB   --zone=europe-west1-b   --type=pd-balanced

# Attach it to the VM
gcloud compute instances attach-disk app-vm   --disk=db-disk   --zone=europe-west1-b
```

---

## 🌐 Step 4 – (If Needed) Open HTTP / HTTPS Firewall Ports

```bash
gcloud compute firewall-rules create allow-http   --allow=tcp:80 --direction=INGRESS --network=default --quiet || true

gcloud compute firewall-rules create allow-https   --allow=tcp:443 --direction=INGRESS --network=default --quiet || true
```

---

## 🔐 Step 5 – SSH into the New VM

```bash
gcloud compute ssh app-vm --zone=europe-west1-b
```

---

## ⚙️ Step 6 – Prevent VM Boot Failure When Attached Disk Is Missing

On reboot, a Google Cloud VM can **hang or fail to boot** if an attached persistent disk (e.g. `db-disk`) isn’t immediately available.  
To ensure the VM always starts, configure the mount so that the system **continues booting even when the disk is missing or delayed**.

---

### 🧩 2. Prepare the Disk Inside the VM

1. **SSH into the VM**
   ```bash
   gcloud compute ssh app-vm --zone=europe-west1-b
   ```

2. **Verify that the attached disk is visible**
   ```bash
   lsblk
   ```
You should see `/dev/sdb` listed.

3. **Format the disk (only the first time)**
   ```bash
   sudo mkfs.ext4 -F /dev/sdb
   ```

4. **Create a mount point**
   ```bash
   sudo mkdir -p /mnt/db
   ```

---

### 🧱 3. Configure a Stable and Safe Mount Using UUID

1. **Get the disk’s UUID**
   ```bash
   sudo blkid /dev/sdb
   ```
Example output:
   ```
   /dev/sdb: UUID="abcd1234-ef56-7890-gh12-ijklmnopqrst" TYPE="ext4"
   ```

2. **Edit the `/etc/fstab` file**
   ```bash
   sudo nano /etc/fstab
   ```

3. **Add this line at the end** (replace the UUID with your own):
   ```bash
   UUID=abcd1234-ef56-7890-gh12-ijklmnopqrst /mnt/db ext4 defaults,nofail,x-systemd.device-timeout=10s 0 2
   ```

**Explanation**
- `UUID=...` → stable disk identifier that does not change between reboots
- `nofail` → allows the VM to continue booting even if the disk is missing or unready
- `x-systemd.device-timeout=10s` → limits how long the system waits for the disk before proceeding

---

### 🔍 4. Validate the Configuration

1. **Test the fstab entry (without rebooting)**
   ```bash
   sudo mount -a
   ```
If no errors appear, the configuration is valid.

2. **Confirm the disk is mounted**
   ```bash
   df -h | grep /mnt/db
   ```

3. **Reboot and verify persistence**
   ```bash
   sudo reboot
   ```

After the VM restarts:
   ```bash
   gcloud compute ssh app-vm --zone=europe-west1-b
   df -h | grep /mnt/db
   ```

✅ **Result:**  
The VM will now **boot cleanly even if the attached disk isn’t ready**.  
The system will continue startup without errors, and the disk will automatically mount as soon as it becomes available.

---

## 🐳 Step 7 – Install Docker + Compose

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo   "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg]   https://download.docker.com/linux/debian   $(. /etc/os-release && echo $VERSION_CODENAME) stable"   | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker

# Verify installation
docker version
docker compose version
```
# 🐳 Push Docker Image to Google Artifact Registry

Perfect 👍 — that means you’re using **Artifact Registry**, which is the new and recommended system.  
So your image path is (repeat the same for frontend instead of backend):

```
docker push europe-west1-docker.pkg.dev/pv-management-app/pv-management-app-repo/backend:latest
```

Here’s the **exact sequence of commands** to push your Docker image there cleanly and verify it works 👇

---

## 🧩 Step-by-Step — Push Docker Image to Artifact Registry

### **1. Authenticate Docker with Artifact Registry**
```bash
gcloud auth configure-docker europe-west1-docker.pkg.dev
```
This updates your Docker config so you can push images securely to Google.

---

### **2. Build your image**
From the root of your project (where your `Dockerfile` is located). Select as TAG either prod or staging:

```bash
TAG=prod

docker build --no-cache -t ghcr.io/markusdunkel/homewatts/backend:${TAG} .
```

📝 **Tip:** You can change `latest` to a version tag (like `v1.0.0`) for better version tracking later.

---

### **3. Push the image to Artifact Registry**
Make sure you’re logged in once:

```bash
echo "$GHCR_PAT" | docker login ghcr.io -u MarkusDunkel --password-stdin
```
then

```bash
docker push ghcr.io/markusdunkel/homewatts/backend:${TAG}
```

You should see output like:
```
The push refers to repository [europe-west1-docker.pkg.dev/pv-management-app/pv-management-app-repo/backend]
latest: Pushed
...
```

---

### **4. Verify the upload**
Check that it exists in your Artifact Registry:
```bash
gcloud artifacts docker tags list europe-west1-docker.pkg.dev/pv-management-app/pv-management-app-repo/backend
```

This will show something like:
```
IMAGE                                                            TAGS     DIGEST
europe-west1-docker.pkg.dev/pv-management-app/pv-management-app-repo/backend  latest  sha256:...
```
---

## ✅ Result

Your image is now **built, versioned, and stored securely** in **Google Artifact Registry**.  
Any VM, Cloud Run service, or Kubernetes cluster in the same project can pull and run it easily.

---

## 📦 Step 8 – Deploy Your App

```bash
cd ~
git clone https://github.com/YOUR_USERNAME/YOUR_REPO.git app
cd app
docker compose up -d
```

or on redeployment: 
```bash
docker compose up -d --no-deps --pull always --force-recreate backend collector frontend
```

Then visit:  
👉 `http://<EXTERNAL_IP>`

Get the external IP address:
```bash
gcloud compute instances describe app-vm   --zone=europe-west1-b   --format='get(networkInterfaces[0].accessConfigs[0].natIP)'
```

also read logs after startup with:  
```bash
docker compose logs backend
```
and make a fresh restart if something doesn't work:
```bash 
docker compose pull
docker compose down
docker compose up -d --force-recreate
```
---