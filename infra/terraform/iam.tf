# ── EC2 인스턴스 프로파일 ──────────────────────────────────────────────

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${var.project_name}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json
}

# SSM Agent가 명령을 받아 가려면 필요하다 (ADR-0021 §결정 3 — SSH 인바운드를 열지 않는 대신 씀)
resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ec2_cloudwatch_agent" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

data "aws_iam_policy_document" "ec2_custom" {
  statement {
    sid    = "EcrPullOnly"
    effect = "Allow"
    actions = [
      "ecr:GetAuthorizationToken",
    ]
    resources = ["*"] # GetAuthorizationToken은 리소스 지정이 불가능한 액션이다
  }

  statement {
    sid    = "EcrPullRepository"
    effect = "Allow"
    actions = [
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  statement {
    sid       = "CommunityImagesBucket"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.community_images.arn}/*"]
  }

  statement {
    sid       = "CommunityImagesBucketList"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.community_images.arn]
  }

  statement {
    sid       = "ParameterStoreRead"
    effect    = "Allow"
    actions   = ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"]
    resources = ["arn:aws:ssm:ap-northeast-2:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}/*"]
    # kms:Decrypt는 별도 정책이 필요 없다 — SecureString 기본 키가 관리형 aws/ssm 키라
    # 이 역할이 그 키에 대한 기본 사용 권한을 이미 갖고 있다.
  }
}

resource "aws_iam_role_policy" "ec2_custom" {
  name   = "${var.project_name}-ec2-custom-policy"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.ec2_custom.json
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project_name}-ec2-instance-profile"
  role = aws_iam_role.ec2.name
}

# ── GitHub OIDC — CD 파이프라인용 ─────────────────────────────────────

# thumbprint_list는 GitHub의 TLS 인증서 체인이 바뀌면 함께 갱신해야 할 수 있다. 아래 두 값은
# AWS·GitHub이 문서화해 온 값이며, OIDC 연동이 sts:AssumeRoleWithWebIdentity에서 실패하면
# 가장 먼저 이 값부터 최신 AWS 가이드와 대조한다.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
}

data "aws_iam_policy_document" "cd_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # sub를 브랜치까지 못박는다 — 레포까지만 제한하면 어떤 브랜치의 워크플로우든 이 역할을
    # 가져갈 수 있다 (ADR-0021 §결정 2).
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/${var.deploy_branch}"]
    }
  }
}

resource "aws_iam_role" "cd_deploy" {
  name               = "${var.project_name}-cd-deploy-role"
  assume_role_policy = data.aws_iam_policy_document.cd_assume_role.json
}

data "aws_iam_policy_document" "cd_deploy_permissions" {
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  statement {
    sid    = "SsmCommand"
    effect = "Allow"
    actions = [
      "ssm:SendCommand",
      "ssm:GetCommandInvocation",
      "ssm:ListCommandInvocations",
    ]
    resources = concat(
      [for instance in aws_instance.web : instance.arn],
      [aws_instance.scheduler.arn],
      ["arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"],
    )
  }

  statement {
    sid    = "AlbRollingDeploy"
    effect = "Allow"
    actions = [
      "elasticloadbalancing:DescribeListeners",
      "elasticloadbalancing:DescribeTargetHealth",
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:RegisterTargets",
      "elasticloadbalancing:DeregisterTargets",
    ]
    resources = [
      aws_lb_listener.https.arn,
      aws_lb_target_group.web.arn,
    ]
  }
}

resource "aws_iam_role_policy" "cd_deploy_permissions" {
  name   = "${var.project_name}-cd-deploy-permissions"
  role   = aws_iam_role.cd_deploy.id
  policy = data.aws_iam_policy_document.cd_deploy_permissions.json
}
