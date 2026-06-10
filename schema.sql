-- ImageAI Original database schema
-- Generated from current backend table initialization code.

create database if not exists image_ai_original
  default charset utf8mb4
  collate utf8mb4_unicode_ci;

use image_ai_original;

create table if not exists default_prompt_settings (
  id bigint primary key,
  main_prompt text not null,
  intro_prompt text not null,
  analysis_prompt text null,
  target_template_prompt text null,
  scene_prompt text null,
  custom_selling_points text null,
  updated_at timestamp not null default current_timestamp on update current_timestamp
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists extra_accessories (
  id bigint primary key auto_increment,
  name varchar(255) not null,
  file_name varchar(255) not null,
  content_type varchar(128) not null,
  file_size bigint not null,
  content longblob not null,
  thumbnail longblob null,
  thumbnail_content_type varchar(128) null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
  index idx_extra_accessories_created (created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists target_templates (
  id bigint primary key auto_increment,
  template_type varchar(16) not null default 'MAIN',
  name varchar(255) not null default '',
  file_name varchar(255) not null default '',
  content_type varchar(128) not null default 'image/jpeg',
  file_size bigint not null default 0,
  content longblob null,
  thumbnail longblob null,
  thumbnail_content_type varchar(128) null,
  style_analysis longtext null,
  model varchar(128) null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
  index idx_target_templates_type_created (template_type, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists image_tasks (
  id varchar(64) primary key,
  product_name varchar(255) not null,
  status varchar(32) not null,
  payload_json longtext not null,
  analysis_json longtext null,
  main_scenes_json longtext null,
  intro_scenes_json longtext null,
  final_main_prompt longtext null,
  final_intro_prompt longtext null,
  thumbnail longblob null,
  thumbnail_content_type varchar(128) null,
  thumbnail_file_name varchar(255) null,
  real_photo_count int not null default 0,
  package_image_count int not null default 0,
  template_count int not null default 0,
  error_message text null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
  started_at timestamp(3) null,
  completed_at timestamp(3) null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists image_task_files (
  id bigint primary key auto_increment,
  task_id varchar(64) not null,
  file_group varchar(32) not null,
  file_name varchar(255) not null,
  content_type varchar(128) not null,
  file_size bigint not null,
  content longblob not null,
  thumbnail longblob null,
  thumbnail_content_type varchar(128) null,
  sort_order int not null default 0,
  created_at timestamp(3) not null default current_timestamp(3),
  index idx_image_task_files_task_group (task_id, file_group),
  constraint fk_image_task_files_task
    foreign key (task_id) references image_tasks(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists image_task_results (
  id bigint primary key auto_increment,
  task_id varchar(64) not null,
  result_type varchar(32) not null,
  item_index int not null,
  status varchar(32) not null,
  prompt longtext not null,
  image_url text null,
  image_base64 longtext null,
  image_path varchar(500) null,
  revised_prompt longtext null,
  raw_response longtext null,
  error_message text null,
  parent_result_id bigint null,
  version_index int not null default 1,
  edit_suggestion text null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
  index idx_image_task_results_task (task_id),
  constraint fk_image_task_results_task
    foreign key (task_id) references image_tasks(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
