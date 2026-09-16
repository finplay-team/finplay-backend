# 프론트 정적 배포는 별도 OIDC 역할과 기존 S3 버킷만 사용한다.
data "aws_iam_policy_document" "frontend_static_deploy_assume_role" {
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

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${split("/", var.github_static_deploy_repository)[0]}@${var.github_organization_id}/${split("/", var.github_static_deploy_repository)[1]}@${var.github_static_deploy_repository_id}:ref:refs/heads/${var.github_static_deploy_branch}"]
    }
  }
}

resource "aws_iam_role" "frontend_static_deploy" {
  name               = "${var.project_name}-frontend-static-deploy-role"
  assume_role_policy = data.aws_iam_policy_document.frontend_static_deploy_assume_role.json
}

data "aws_iam_policy_document" "frontend_static_deploy_permissions" {
  statement {
    sid       = "ListSiteSyncPrefix"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.frontend.arn]
  }

  statement {
    sid       = "SyncSiteObjects"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]
  }
}

resource "aws_iam_role_policy" "frontend_static_deploy_permissions" {
  name   = "${var.project_name}-frontend-static-deploy-policy"
  role   = aws_iam_role.frontend_static_deploy.id
  policy = data.aws_iam_policy_document.frontend_static_deploy_permissions.json
}
