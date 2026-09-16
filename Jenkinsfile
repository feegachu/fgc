// fgc CI/CD (Jenkins, 교안 3일차) — Checkout → Build 3 images → Push zot → Deploy dev VM Pod
// Jenkins 는 dev VM 의 컨테이너(localhost/jenkins/jenkins:v1, podman·buildah·sshpass 포함)로 돈다.
// 컨테이너 안에서 본 dev VM = host.containers.internal. zot 은 인증 없음(--tls-verify=false, http).
// Credentials: gitea(Gitea 계정), vdi-user(VM ssh 계정). Docker Hub 로그인은 pull 제한이 날 때만(docker).
pipeline {
    agent any

    parameters {
        string(name: 'TAG', defaultValue: 'v1', description: 'Image tag (dev: v1 / 단계: a0.1, b0.1, r0.1)')
    }

    environment {
        REGISTRY   = 'host.containers.internal:5000'     // Jenkins 컨테이너에서 본 zot
        IMAGE_BASE = "${REGISTRY}/fgc"
        DEV_HOST   = 'host.containers.internal'          // Jenkins 컨테이너에서 본 dev VM
        POD_YAML   = 'podman/fgc-pod.yaml'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                sh 'git rev-parse --short=12 HEAD'
            }
        }

        stage('Build Images') {
            steps {
                sh '''
                set -e
                for i in db api web; do
                  podman build --cgroup-manager=cgroupfs -f Containerfile.$i -t ${IMAGE_BASE}/$i:${TAG} .
                done
                podman images | grep fgc
                '''
            }
        }

        stage('Push Images') {
            steps {
                sh '''
                set -e
                for i in db api web; do
                  podman push --tls-verify=false ${IMAGE_BASE}/$i:${TAG}
                done
                '''
            }
        }

        stage('Deploy to dev VM') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'vdi-user',
                    usernameVariable: 'SSH_USER',
                    passwordVariable: 'SSH_PASS'
                )]) {
                    sh '''
                    set +x   # 비밀번호가 Console 에 안 찍히게(마스킹 외 이중 안전장치)
                    set -e
                    sed "s/__TAG__/${TAG}/g" ${POD_YAML} > /tmp/fgc-pod.yaml
                    sshpass -p "${SSH_PASS}" scp -o StrictHostKeyChecking=no /tmp/fgc-pod.yaml ${SSH_USER}@${DEV_HOST}:/tmp/fgc-pod.yaml
                    # Pod 는 root 소유(수동 배포와 동일). sudo -S 로 비밀번호를 stdin 으로 넘긴다 — Console 에 안 찍힘
                    sshpass -p "${SSH_PASS}" ssh -o StrictHostKeyChecking=no ${SSH_USER}@${DEV_HOST} \
                      "echo '${SSH_PASS}' | sudo -S podman kube play --network podman --tls-verify=false --replace /tmp/fgc-pod.yaml && for i in \$(seq 1 40); do curl -sf -o /dev/null http://localhost:8088/login && break; sleep 3; done && curl -sfI http://localhost:8088/login | head -1"
                    '''
                }
            }
        }
    }

    post {
        success { echo "Deploy complete: ${IMAGE_BASE}/{db,api,web}:${params.TAG}" }
        failure { echo "Deploy failed" }
    }
}
